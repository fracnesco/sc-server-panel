// =====================================================================
// SPRouting - who writes which bus, who reads it, and in what order
//
// The node tree read as a signal flow. From each synth's SynthDef (its In
// and Out UGens) and its control values, a model of the busses: for every
// bus channel the synths that write it and those that read it, in the
// order the server runs them. Language only - nothing here touches the
// server. SPTree builds one at every answer of the server and shows it as
// the busses view, the problems, and the path of a signal.
//
//   m = SPRouting.build(s, tree);            // tree as SPTree parses it
//   SPRouting.ranges(m);                     // the busses, stereo pairs joined
//   SPRouting.problems(m);                   // a reader that runs before its writer ...
//   SPRouting.pathFrom(m, (kind: \synth, id: 1004), levels);
//
// What it can not know: a synth whose SynthDef this sclang has no SynthDesc
// of (m[\unknown] counts them), a bus worked out inside the graph, and a
// bus a control is mapped to.
//
// The order rule it checks is the server's: In.ar hears a bus only if it
// was written earlier in the same block, by a synth before it in the tree.
// A reader placed before its writers hears silence (InFeedback hears the
// block before, wherever it is).
// =====================================================================

SPRouting {
	classvar templates;		// def -> [its SynthDesc, [(dir, rate, channels, type, start) ...]]
	classvar <>silence = 0.0001;		// -80 dB: under this a bus is silent

	*initClass { templates = IdentityDictionary.new }

	// ------------------------------------------------------------ one synth

	// A def's In and Out UGens: start is a bus number, the name of the control
	// that says it, or nil when the graph works it out. Nil without a SynthDesc.
	*templateOf {
		|def|
		var desc = SPInspector.descOf(def), cached, list = [];
		if (desc.isNil) { ^nil };
		cached = templates[def.asSymbol];
		if (cached.notNil and: { cached[0] === desc }) { ^cached[1] };
		[[desc.inputs, \in], [desc.outputs, \out]].do { |pair|
			(pair[0] ? []).do { |io|
				var type = io.type.name, start = io.startingChannel;
				if (#[\LocalIn, \LocalOut].includes(type).not) {
					start = case
					{ start.isNumber } { start.asInteger }
					{ start.isNil or: { start.asString == "?" } } { nil }
					{ true } { start.asSymbol };
					list = list.add((dir: pair[1], rate: io.rate, channels: io.numberOfChannels.asInteger.max(1), type: type, start: start))
				}
			}
		};
		templates[def.asSymbol] = [desc, list];
		^list
	}

	// control name -> value: the def's defaults, then what the server said
	*controlDict {
		|def, controls|
		var dict = IdentityDictionary.new, desc = SPInspector.descOf(def);
		desc !? { desc.controls.do { |c| dict[c.name.asSymbol] = c.defaultValue.asArray[0] } };
		(controls ? []).do { |p| if (p[0].isKindOf(Symbol) or: { p[0].isString }) { dict[p[0].asSymbol] = p[1] } };
		^dict
	}

	// A synth's busses with their numbers: (dir, rate, channels, type, bus, via).
	// bus is nil when it is not a plain number (mapped, or made in the graph).
	*iosOf {
		|def, controls|
		var tpl = this.templateOf(def), dict;
		if (tpl.isNil) { ^nil };
		^tpl.collect { |t|
			var bus = t[\start], via;
			if (bus.isKindOf(Symbol)) {
				via = bus;
				dict = dict ?? { this.controlDict(def, controls) };
				bus = dict[via];
				bus = if (bus.isNumber) { bus.asInteger } { nil };
			};
			if (bus.notNil and: { bus < 0 }) { bus = nil };
			(dir: t[\dir], rate: t[\rate], channels: t[\channels], type: t[\type], bus: bus, via: via)
		}
	}

	// the buffers a synth's controls name: [[control, bufnum] ...] - a control
	// whose name says buf and whose value is a whole number
	*buffersOf {
		|def, controls|
		var out = [];
		(controls ? []).do { |p|
			var name = p[0].asString.toLower, v = p[1];
			if (name.contains("buf") and: { v.isNumber } and: { v >= 0 } and: { v == v.round }) {
				out = out.add([p[0], v.asInteger])
			}
		};
		^out
	}

	// ------------------------------------------------------------ the model

	// The whole tree as busses. tree is a node Event (id, kind, def, controls,
	// children); controlsOf, if given, answers a synth's controls when its
	// node has none.
	*build {
		|server, tree, controlsOf|
		var order = List.new, nodes = IdentityDictionary.new, ios = IdentityDictionary.new, pos = IdentityDictionary.new;
		var audio = IdentityDictionary.new, control = IdentityDictionary.new, unknown = 0, walk;
		walk = { |n| if (n[\kind] == \synth) { order.add(n) } { n[\children].do(walk) } };
		tree !? { walk.value(tree) };
		order.do { |n, k|
			var list = this.iosOf(n[\def], n[\controls] ?? { controlsOf.value(n[\id]) });
			pos[n[\id]] = k;
			nodes[n[\id]] = n;
			if (list.isNil) { unknown = unknown + 1 } {
				ios[n[\id]] = list;
				list.do { |io|
					io[\bus] !? { |b|
						var dict = if (io[\rate] == \audio) { audio } { control };
						io[\channels].do { |c|
							var e = dict[b + c];
							if (e.isNil) {
								e = (rate: io[\rate], index: b + c, writers: List.new, readers: List.new);
								dict[b + c] = e;
							};
							if (io[\dir] == \out) {
								e[\writers].add((id: n[\id], type: io[\type]))
							} {
								e[\readers].add((id: n[\id], type: io[\type]))
							};
						}
					}
				}
			}
		};
		^(server: server, order: order.array, nodes: nodes, pos: pos, ios: ios, audio: audio, control: control, unknown: unknown)
	}

	// \out (hardware output), \in (hardware input), \private or \control
	*busKind {
		|server, rate, index|
		var outs = server.options.numOutputBusChannels, ins = server.options.numInputBusChannels;
		if (rate != \audio) { ^\control };
		^case { index < outs } { \out } { index < (outs + ins) } { \in } { true } { \private }
	}

	*prIds { |list| ^list.collect { |x| x[\id] }.asArray }

	// The busses as rows: neighbouring channels with the same writers and
	// readers are one row (a stereo pair). (rate, index, channels, kind,
	// writers, readers, name), audio first, by index.
	*ranges {
		|model|
		var out = List.new, server = model[\server];
		[[\audio, model[\audio]], [\control, model[\control]]].do { |pair|
			var rate = pair[0], dict = pair[1], last;
			dict.keys.asArray.sort.do { |index|
				var e = dict[index], w = this.prIds(e[\writers]), r = this.prIds(e[\readers]);
				var kind = this.busKind(server, rate, index);
				if (last.notNil and: { (last[\index] + last[\channels]) == index } and: { last[\kind] == kind }
					and: { last[\w] == w } and: { last[\r] == r }) {
					last[\channels] = last[\channels] + 1;
				} {
					last = (rate: rate, index: index, channels: 1, kind: kind, w: w, r: r,
						writers: e[\writers].asArray, readers: e[\readers].asArray,
						name: SPTree.busName(server, rate, index));
					out.add(last);
				}
			}
		};
		^out.array
	}

	// "audio 16-17 (orbit 0 dry)"
	*busText {
		|server, rate, index, channels = 1, name|
		name = name ?? { SPTree.busName(server, rate, index) };
		^"% %".format(rate, if (channels > 1) { "%-%".format(index, index + channels - 1) } { index })
			++ (name !? { " (" ++ name ++ ")" } ? "")
	}

	*nodeText {
		|model, id|
		var n = model[\nodes][id];
		^if (n.isNil) { "node %".format(id) } { "% (%)".format(n[\def], id) }
	}

	// Several nodes in words: "nt_src (1004)" for one of a kind, "nt_blip ×3"
	// for more - ids that come and go would make the sentence flicker.
	*whoText {
		|model, ids|
		var counts = Dictionary.new, first = Dictionary.new, order = List.new;
		ids.do { |id|
			var def = model[\nodes][id] !? { |n| n[\def] } ? id;
			if (counts[def].isNil) { order.add(def); first[def] = id };
			counts[def] = (counts[def] ? 0) + 1;
		};
		^order.collect { |def|
			if (counts[def] > 1) { "% ×%".format(def, counts[def]) } { this.nodeText(model, first[def]) }
		}.join(", ")
	}

	// ------------------------------------------------------------- problems

	// What looks wrong in the order: Events (level: \warn or \info, kind, id,
	// bus: [rate, index], text, key). A key stays the same while the problem
	// does, so a list of them does not flicker.
	*problems {
		|model|
		var out = List.new, server = model[\server], early = Dictionary.new, pos = model[\pos];
		// a reader before its writers
		model[\audio].keysValuesDo { |index, e|
			if (this.busKind(server, \audio, index) != \in) {
				e[\readers].do { |r|
					if (r[\type] == \In) {
						var rp = pos[r[\id]], others = e[\writers].reject { |w| w[\id] == r[\id] };
						var before = others.select { |w| pos[w[\id]] < rp }, after = others.select { |w| pos[w[\id]] > rp };
						if (after.notEmpty) {
							var ids = this.prIds(after).asSet.asArray.sort;
							var key = [\early, r[\id], before.isEmpty];
							var entry = early[key] ?? { early[key] = (indices: List.new, ids: IdentitySet.new); early[key] };
							entry[\indices].add(index);
							ids.do { |w| entry[\ids].add(w) };
						}
					}
				}
			}
		};
		early.keysValuesDo { |key, entry|
			var id = key[1], all = key[2], ids = entry[\ids].asArray.sort, indices = entry[\indices].asArray.asSet.asArray.sort, first = indices.minItem;
			var bus = this.busText(server, \audio, first, indices.size);
			var who = this.whoText(model, ids);
			out.add((level: if (all) { \warn } { \info }, kind: \early, id: id, bus: [\audio, first], key: key.asCompileString,
				text: if (all) {
					"% reads % before anything has written it in this block, so it hears silence. % written later by %. Move it after, or read with InFeedback."
						.format(this.nodeText(model, id), bus, if (ids.size > 1) { "They are" } { "It is" }, who)
				} {
					"% misses what % writes to %: that comes after it in the tree.".format(this.nodeText(model, id), who, bus)
				}))
		};
		// a bus with only one end
		this.ranges(model).do { |r|
			if (r[\kind] == \private) {
				var bus = this.busText(server, r[\rate], r[\index], r[\channels], r[\name]);
				if (r[\readers].isEmpty and: { r[\writers].notEmpty }) {
					out.add((level: \info, kind: \unread, bus: [r[\rate], r[\index]], id: r[\writers][0][\id],
						key: [\unread, r[\index]].asCompileString,
						text: "% is written by % and read by nothing in the tree.".format(bus,
							this.whoText(model, r[\w].asSet.asArray.sort))))
				};
				if (r[\writers].isEmpty and: { r[\readers].notEmpty }) {
					out.add((level: \info, kind: \unwritten, bus: [r[\rate], r[\index]], id: r[\readers][0][\id],
						key: [\unwritten, r[\index]].asCompileString,
						text: "% is read by % and written by nothing right now.".format(bus,
							this.whoText(model, r[\r].asSet.asArray.sort))))
				};
			}
		};
		^out.array
	}

	// --------------------------------------------------------------- a path

	// Where a signal goes: from a synth, or a bus, through every reader that
	// hears it, to the hardware outputs. start is (kind: \synth, id:) or
	// (kind: \bus, rate:, index:, channels:). levels is a Function that answers
	// a bus channel's level, or nil when it is not measured (levels nil: nothing
	// is); paused the ids of paused synths.
	//
	// Answers (rows:, verdicts:). A row is (step:, depth:, ...): \synth (id,
	// paused), \bus (rate, index, channels, kind, level, end), \note (text, id).
	// A verdict is (ok: Boolean, text:).
	*pathFrom {
		|model, start, levels, paused|
		var rows = List.new, leaves = List.new, server = model[\server], pos = model[\pos];
		var levelOf, synthStep, busStep, limit = 400, verdicts;
		paused = paused ?? { IdentitySet.new };
		levelOf = { |index, channels|
			var vals = channels.collect { |c| levels !? { levels.value(index + c) } }.reject(_.isNil);
			if (vals.isEmpty) { nil } { vals.maxItem }
		};
		synthStep = { |id, depth, visited, trail|
			var outs, row, done = Set.new;
			row = (step: \synth, depth: depth, id: id, paused: paused.includes(id));
			if (rows.size < limit) {
				rows.add(row);
				trail = trail ++ [row];
				case
				{ visited.includes(id) } {
					rows.add((step: \note, depth: depth + 1, text: "a loop: it is already above", id: id));
					leaves.add((status: \loop, trail: trail));
				}
				{ model[\ios][id].isNil } {
					rows.add((step: \note, depth: depth + 1, text: "its SynthDef is not known to this sclang: where it writes can not be followed", id: id));
					leaves.add((status: \unknown, trail: trail));
				}
				{ true } {
					outs = model[\ios][id].select { |io| io[\dir] == \out and: { io[\rate] == \audio } };
					if (outs.isEmpty) {
						rows.add((step: \note, depth: depth + 1, text: "it writes no audio bus", id: id));
						leaves.add((status: \dead, trail: trail));
					};
					outs.do { |io|
						if (io[\bus].isNil) {
							rows.add((step: \note, depth: depth + 1, id: id,
								text: "it writes a bus that is worked out in its graph%: that can not be followed".format(
									io[\via] !? { |v| " (control " ++ v ++ ")" } ? "")));
							leaves.add((status: \unknown, trail: trail));
						} {
							var key = [io[\bus], io[\channels]];
							if (done.includes(key).not) {
								done.add(key);
								busStep.value(io[\bus], io[\channels], id, depth + 1, visited.copy.add(id), trail);
							}
						}
					};
				};
			};
		};
		busStep = { |index, channels, from, depth, visited, trail|
			var kind = this.busKind(server, \audio, index), row, readers = List.new, seen = IdentitySet.new, later, earlyOnes, fromPos;
			row = (step: \bus, depth: depth, rate: \audio, index: index, channels: channels, kind: kind,
				level: levelOf.value(index, channels), end: kind == \out);
			if (rows.size < limit) {
				rows.add(row);
				trail = trail ++ [row];
				if (kind == \out) {
					leaves.add((status: \out, trail: trail));
				} {
					channels.do { |c|
						(model[\audio][index + c] !? { |e| e[\readers] } ? []).do { |r|
							if (r[\id] != from and: { seen.includes(r[\id]).not }) { seen.add(r[\id]); readers.add(r) }
						}
					};
					fromPos = from !? { pos[from] } ? -1;
					later = readers.select { |r| r[\type] != \In or: { pos[r[\id]] > fromPos } };
					earlyOnes = readers.reject { |r| later.includes(r) };
					earlyOnes.do { |r|
						rows.add((step: \note, depth: depth + 1, id: r[\id], warn: true,
							text: "% reads it too early: it runs before this writer and hears none of it".format(this.nodeText(model, r[\id]))))
					};
					if (later.isEmpty) {
						rows.add((step: \note, depth: depth + 1, text: "nothing reads this bus after that", warn: true));
						leaves.add((status: \unread, trail: trail));
					};
					later.sort { |a, b| pos[a[\id]] < pos[b[\id]] }.do { |r| synthStep.value(r[\id], depth + 1, visited, trail) };
				};
			};
		};
		if (start[\kind] == \bus) {
			busStep.value(start[\index], start[\channels] ? 1, nil, 0, IdentitySet.new, []);
		} {
			synthStep.value(start[\id], 0, IdentitySet.new, []);
		};
		verdicts = this.prVerdicts(model, leaves.array, levels.notNil);
		^(rows: rows.array, verdicts: verdicts, truncated: rows.size >= limit)
	}

	// what each way out comes to, in words: the first place the sound stops
	*prVerdicts {
		|model, leaves, measured|
		var ok = List.new, stops = List.new, server = model[\server], add;
		add = { |list, text| if (list.includes(text).not) { list.add(text) } };
		leaves.do { |leaf|
			var trail = leaf[\trail], stop, lastSynth, lastBus, sounding = nil;
			trail.do { |step|
				if (stop.isNil) {
					if (step[\step] == \synth) {
						if (step[\paused]) { stop = "% is paused: nothing gets past it".format(this.nodeText(model, step[\id])) };
						lastSynth = step;
					} {
						if (step[\level].notNil) {
							if (step[\level] < silence) {
								stop = case
								{ lastBus.isNil and: { lastSynth.notNil } } {
									"nothing is coming out of % right now: % is silent".format(this.nodeText(model, lastSynth[\id]),
										this.busText(server, \audio, step[\index], step[\channels]))
								}
								{ lastBus.isNil } { "% is silent right now".format(this.busText(server, \audio, step[\index], step[\channels])) }
								{ true } {
									"the sound stops in %: % going in has signal, % coming out is silent".format(
										this.nodeText(model, lastSynth[\id]),
										this.busText(server, \audio, lastBus[\index], lastBus[\channels]),
										this.busText(server, \audio, step[\index], step[\channels]))
								};
							} { sounding = step };
						};
						lastBus = step;
					}
				}
			};
			case
			{ stop.notNil } { add.value(stops, stop) }
			{ leaf[\status] == \out } {
				add.value(ok, "it reaches %%".format(this.busText(server, \audio, trail.last[\index], trail.last[\channels]),
					case { measured.not } { " (levels not measured)" } { sounding.notNil } { ", with signal all the way" } { "" }))
			}
			{ leaf[\status] == \unread } {
				add.value(stops, "it ends at %: nothing reads that bus after it is written".format(
					this.busText(server, \audio, trail.last[\index], trail.last[\channels])))
			}
			{ leaf[\status] == \dead } { add.value(stops, "% writes no audio bus".format(this.nodeText(model, trail.last[\id]))) }
			{ leaf[\status] == \loop } { add.value(stops, "one way round is a feedback loop") }
			{ true } { add.value(stops, "one way out can not be followed (a bus worked out in a graph, or a SynthDef not known here)") };
		};
		^ok.collect { |t| (ok: true, text: t) }.asArray ++ stops.collect { |t| (ok: false, text: t) }.asArray
	}

	// who feeds a synth: [(rate, index, channels, writers: [ids]) ...] for its inputs
	*feedsOf {
		|model, id|
		var out = [], pos = model[\pos];
		(model[\ios][id] ? []).do { |io|
			if (io[\dir] == \in and: { io[\bus].notNil }) {
				var dict = if (io[\rate] == \audio) { model[\audio] } { model[\control] }, ids = IdentitySet.new;
				io[\channels].do { |c|
					(dict[io[\bus] + c] !? { |e| e[\writers] } ? []).do { |w|
						if (w[\id] != id and: { io[\type] != \In or: { io[\rate] != \audio } or: { pos[w[\id]] < pos[id] } }) { ids.add(w[\id]) }
					}
				};
				out = out.add((rate: io[\rate], index: io[\bus], channels: io[\channels], type: io[\type],
					writers: ids.asArray.sort { |a, b| pos[a] < pos[b] }));
			}
		};
		^out
	}
}
