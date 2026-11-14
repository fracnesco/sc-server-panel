// =====================================================================
// SPLog, SPHealth, SPWatch, SPSnapshot - what SPTree keeps beside the tree
//
//   SPLog       every node that started, ended, was paused or resumed, every
//               FAILURE the server sent, every problem that came and went:
//               a line each, with the time. Bursts of the same thing (a
//               pattern's events) are one line with a count.
//   SPHealth    what only shows over time: a SynthDef whose synths pile up,
//               a synth that outlives the others of its kind, a paused synth
//               left beside its running replacement.
//   SPWatch     a few controls and busses whose values you want in view,
//               each with the last half minute as a line.
//   SPSnapshot  the whole tree at one moment - nodes, controls, busses,
//               levels, problems - as an Event; two of them compared; one
//               written to disk as an archive and as text.
//
// All four are language only. SPTree owns one of each and feeds them.
// =====================================================================

SPLog {
	classvar <>maxEntries = 3000, <>burstTime = 1.0, <>burstSpan = 15;

	var <entries, serial = 0, <startedAt;
	var <>onAdd;		// { |entry| } called with every entry as it is added

	*new { ^super.new.init }

	init { entries = List.new; startedAt = Main.elapsedTime }

	// kind: \born \ended \paused \resumed \fail \problem \cleared \snapshot \note
	// more: an Event of whatever else is known (id, def, pathText, parent, lived, text, level)
	add {
		|kind, more|
		var e = (more ? ()).copy;
		serial = serial + 1;
		e[\kind] = kind;
		e[\time] = e[\time] ? Main.elapsedTime;
		e[\serial] = serial;
		entries.add(e);
		if (entries.size > (maxEntries + 200)) { entries = entries.copyRange(entries.size - maxEntries, entries.size - 1).asList };
		onAdd.value(e);
		^e
	}

	clear { entries.clear }

	size { ^entries.size }

	// entries since a time, oldest first
	since { |time| ^entries.select { |e| e[\time] >= time } }

	// The entries as rows, newest first. With bursts, entries of one kind,
	// def and place that follow each other less than burstTime apart are one
	// row with a count - whatever else happened in between - for burstSpan
	// seconds at most, so a long pattern is a row every few seconds.
	rows {
		|bursts = true, filter = ""|
		var out = List.new, open = Dictionary.new, text = filter.toLower;
		entries.do { |e|
			if (text.isEmpty or: { this.class.lineOf(e).toLower.contains(text) }) {
				var key = [e[\kind], e[\def], e[\pathText], e[\nodeKind]], last = open[key];
				if (bursts and: { #[\born, \ended].includes(e[\kind]) } and: { last.notNil }
					and: { (e[\time] - last[\lastTime]) <= burstTime } and: { (e[\time] - last[\time]) <= burstSpan }) {
					last[\count] = last[\count] + 1;
					last[\lastTime] = e[\time];
					if (e[\lived].notNil) { last[\livedSum] = (last[\livedSum] ? 0) + e[\lived] };
				} {
					last = e.copy;
					last[\count] = 1;
					last[\lastTime] = e[\time];
					last[\livedSum] = e[\lived];
					out.add(last);
					open[key] = last;
				}
			}
		};
		^out.array.reverse
	}

	// hh:mm:ss.t of an elapsed time, on the wall clock
	*clock {
		|time|
		var d = Date.getDate, now = (d.hour * 3600) + (d.minute * 60) + d.second + (d.rawSeconds % 1);
		var t = (now - (Main.elapsedTime - time)) % 86400;
		^"%:%:%.%".format((t / 3600).floor.asInteger.asString.padLeft(2, "0"),
			((t % 3600) / 60).floor.asInteger.asString.padLeft(2, "0"),
			(t % 60).floor.asInteger.asString.padLeft(2, "0"),
			((t % 1) * 10).floor.asInteger)
	}

	*what {
		|e|
		^switch(e[\kind],
			\born, { "started" },
			\ended, { "ended" },
			\paused, { "paused" },
			\resumed, { "resumed" },
			\fail, { "FAILURE" },
			\problem, { "problem" },
			\cleared, { "cleared" },
			\snapshot, { "snapshot" },
			{ e[\kind].asString })
	}

	// one entry (or one burst row) as a line of text, without the time
	*lineOf {
		|e|
		var count = e[\count] ? 1, who;
		who = case
		{ e[\def].notNil } { "% %".format(e[\def], if (count > 1) { "×" ++ count } { e[\id] ? "" }) }
		{ e[\id].notNil and: { count > 1 } } { "% ×%".format(e[\nodeKind] ? "node", count) }
		// a problem's or a failure's text names its node itself
		{ #[\problem, \fail, \cleared].includes(e[\kind]) } { "" }
		{ e[\id].notNil } { "% %".format(e[\nodeKind] ? "node", e[\label] !? { |l| "% (%)".format(l, e[\id]) } ? e[\id]) }
		{ true } { "" };
		^"%  %%%%".format(this.what(e).padRight(8), who,
			e[\pathText] !? { |p| if (p.isEmpty) { "" } { "  in " ++ p } } ? "",
			if (e[\livedSum].notNil) {
				"  ·  lived %".format(SPInspector.duration(e[\livedSum] / count)) ++ if (count > 1) { " each" } { "" }
			} { "" } ++ if (count > 1) { "  ·  over %".format(SPInspector.duration((e[\lastTime] ? e[\time]) - e[\time])) } { "" },
			e[\text] !? { |t| if (who.isEmpty) { t } { "  ·  " ++ t } } ? "")
	}

	// the whole log as text, oldest first
	text {
		^entries.collect { |e| this.class.clock(e[\time]) ++ "  " ++ this.class.lineOf(e) }.join("\n")
	}
}


SPHealth {
	classvar <>sampleEvery = 5, <>samples = 12;		// a count every 5 s, a minute of them
	classvar <>pausedFor = 20, <>outlives = 20, <>outlivesAtLeast = 30;

	var counts, lastSample = 0, <problems;

	*new { ^super.new.init }

	init { counts = Dictionary.new; problems = [] }

	// order: the synths running (node Events), in tree order. known: id -> record
	// with born. history: ended records (born, ended, def). paused: id -> since.
	// ios: id -> a synth's busses (SPRouting). Answers the problems found.
	check {
		|order, known, history, paused, ios, now|
		var byDef = Dictionary.new, out = List.new, lifetimes = Dictionary.new;
		now = now ? Main.elapsedTime;
		order.do { |n| byDef[n[\def]] = (byDef[n[\def]] ? []).add(n) };

		// a def whose count only grows
		if ((now - lastSample) >= sampleEvery) {
			lastSample = now;
			(byDef.keys.asArray ++ counts.keys.asArray).asSet.do { |def|
				var list = (counts[def] ? []).add((byDef[def] ? []).size);
				counts[def] = list.keep(samples.neg);
				if (counts[def].every { |c| c == 0 }) { counts.removeAt(def) };
			};
		};
		counts.keysValuesDo { |def, list|
			if (list.size >= 5) {
				var first = list.first, last = list.last, growing = true;
				(list.size - 1).do { |k| if (list[k + 1] < list[k]) { growing = false } };
				if (growing and: { (last - first) >= max(6, first * 0.25) }) {
					out.add((level: \warn, kind: \growing, def: def, id: (byDef[def] ? []).last !? { |n| n[\id] },
						key: [\growing, def].asCompileString,
						text: "% keeps piling up: % synths now, % % ago, never fewer in between. Something starts them and nothing frees them."
							.format(def, last, first, SPInspector.duration((list.size - 1) * sampleEvery))))
				}
			}
		};

		// a synth that outlives the others of its kind
		history.do { |r|
			if (r[\born].notNil and: { r[\ended].notNil }) {
				lifetimes[r[\def]] = (lifetimes[r[\def]] ? []).add(r[\ended] - r[\born])
			}
		};
		lifetimes.keysValuesDo { |def, lives|
			if (lives.size >= 5) {
				var median = lives.copy.sort[lives.size div: 2];
				if (median < 5) {
					(byDef[def] ? []).do { |n|
						var born = known[n[\id]] !? { |r| r[\born] }, age = born !? { now - born };
						if (age.notNil and: { age > max(outlives * median, outlivesAtLeast) }) {
							out.add((level: \warn, kind: \outlives, id: n[\id], def: def, key: [\outlives, n[\id]].asCompileString,
								text: "% (%) has been there for %; the others of its kind end after about %. It may never have been released."
									.format(def, n[\id], SPInspector.duration(age), SPInspector.duration(median))))
						}
					}
				}
			}
		};

		// a paused synth beside a running one of the same def on the same busses
		paused.keysValuesDo { |id, since|
			var n = order.detect { |x| x[\id] == id };
			if (n.notNil and: { (now - since) > pausedFor }) {
				var sig = this.class.signature(ios[id]);
				var twin = (byDef[n[\def]] ? []).detect { |x|
					x[\id] != id and: { paused[x[\id]].isNil } and: { x[\parent] == n[\parent] }
					and: { this.class.signature(ios[x[\id]]) == sig }
				};
				if (twin.notNil) {
					out.add((level: \warn, kind: \stuck, id: id, def: n[\def], key: [\stuck, id].asCompileString,
						text: "% (%) has been paused for %, and % runs in its place on the same busses. It was released while asleep and never freed: it holds its memory until cmd + period."
							.format(n[\def], id, SPInspector.duration(now - since), twin[\id])))
				}
			}
		};
		problems = out.array;
		^problems
	}

	*signature { |ios| ^(ios ? []).collect { |io| [io[\dir], io[\rate], io[\bus], io[\channels]] } }

	// the counts of a def, oldest first
	countsOf { |def| ^counts[def] ? [] }
}


SPWatch {
	classvar <>historySize = 150;

	var <items;

	*new { ^super.new.init }

	init { items = List.new }

	// what: (kind: \control, id:, index:, name:, def:) or (kind: \bus, rate:, index:, name:)
	add {
		|what|
		var key = this.class.keyOf(what), item;
		items.detect { |i| i[\key] == key } !? { |i| ^i };
		item = what.copy;
		item[\key] = key;
		item[\hist] = List.new;
		items.add(item);
		^item
	}

	remove { |key| items.removeAllSuchThat { |i| i[\key] == key } }

	includes { |what| var key = this.class.keyOf(what); ^items.any { |i| i[\key] == key } }

	toggle { |what| if (this.includes(what)) { this.remove(this.class.keyOf(what)) } { this.add(what) } }

	isEmpty { ^items.isEmpty }
	size { ^items.size }

	*keyOf {
		|what|
		^if (what[\kind] == \control) { "c_%_%".format(what[\id], what[\index]) } { "b_%_%".format(what[\rate], what[\index]) }.asSymbol
	}

	// a value arrived for an item
	put {
		|key, value|
		items.detect { |i| i[\key] == key } !? { |i|
			i[\value] = value;
			i[\hist].add(value);
			while { i[\hist].size > historySize } { i[\hist].removeAt(0) };
		}
	}

	controls { ^items.select { |i| i[\kind] == \control } }
	busses { ^items.select { |i| i[\kind] == \bus } }

	*labelOf {
		|item|
		^if (item[\kind] == \control) {
			"% % · %".format(item[\def] ? "synth", item[\id], item[\name])
		} {
			"% bus %%".format(item[\rate], item[\index], item[\name] !? { |n| " · " ++ n } ? "")
		}
	}
}


SPSnapshot {
	classvar <>folder;

	*initClass {
		Class.initClassTree(Platform);
		folder = Platform.userAppSupportDir +/+ "server-panel-snapshots";
	}

	// a tree node and everything under it, copied: id, kind, def, controls, children
	*copyTree {
		|node|
		var out = (id: node[\id], kind: node[\kind], parent: node[\parent], path: node[\path]);
		node[\def] !? { |d| out[\def] = d };
		node[\controls] !? { |c| out[\controls] = c.collect(_.copy) };
		if (node[\kind] == \group) { out[\children] = (node[\children] ? []).collect { |c| this.copyTree(c) } };
		^out
	}

	// The moment as an Event. names: id -> what a describer calls it; levels:
	// bus index -> peak; problems as SPTree lists them.
	*make {
		|server, tree, labels, names, model, levels, problems, paused|
		var date = Date.getDate, ranges = SPRouting.ranges(model);
		^(
			stamp: date.stamp,
			clock: date.format("%H:%M:%S"),
			elapsed: Main.elapsedTime,
			server: server.name,
			tree: this.copyTree(tree),
			labels: labels.copy,
			names: names.copy,
			paused: (paused ? []).asArray,
			levels: levels.copy,
			unknown: model[\unknown],
			busses: ranges.collect { |r|
				(rate: r[\rate], index: r[\index], channels: r[\channels], kind: r[\kind], name: r[\name],
					writers: r[\w].asSet.asArray.sort, readers: r[\r].asSet.asArray.sort,
					level: if (r[\rate] == \audio) { r[\channels].collect { |c| levels[r[\index] + c] }.reject(_.isNil).maxItem })
			},
			problems: (problems ? []).collect { |p| (level: p[\level], kind: p[\kind], id: p[\id], text: p[\text], key: p[\key]) }
		)
	}

	*counts {
		|snap|
		var g = 0, s = 0, walk;
		walk = { |n| if (n[\kind] == \group) { g = g + 1 } { s = s + 1 }; n[\children].do(walk) };
		walk.value(snap[\tree]);
		^[g - 1, s]
	}

	*title { |snap| var c = this.counts(snap); ^"%  ·  % synths, % groups".format(snap[\clock], c[1], c[0]) }

	// id -> node, and the ids in tree order
	*index {
		|snap|
		var byId = IdentityDictionary.new, order = List.new, walk;
		walk = { |n| byId[n[\id]] = n; order.add(n[\id]); n[\children].do(walk) };
		walk.value(snap[\tree]);
		^[byId, order.array]
	}

	*nameOf {
		|snap, node|
		^if (node[\kind] == \group) {
			"group % %".format(node[\id], snap[\labels][node[\id]] ? "").stripWhiteSpace
		} {
			"% (%)".format(node[\def], node[\id]) ++ (snap[\names][node[\id]] !? { |n| " - " ++ n } ? "")
		}
	}

	// What changed from a to b: Events (kind: \added \removed \moved \control
	// \bus \problem, id, text), in that order.
	*diff {
		|a, b|
		var ia = this.index(a), ib = this.index(b), byA = ia[0], byB = ib[0], out = List.new;
		var common, seqA, seqB, kept, moved = IdentitySet.new;
		// what came and what went: several of one SynthDef in one place are one line
		this.prGrouped(ib[1].select { |id| byA[id].isNil }, byB, b, \added, "in").do { |c| out.add(c) };
		this.prGrouped(ia[1].select { |id| byB[id].isNil }, byA, a, \removed, "from").do { |c| out.add(c) };
		// moved: another parent, or out of step with the others that stayed
		common = ia[1].select { |id| byB[id].notNil };
		common.do { |id| if (byA[id][\parent] != byB[id][\parent]) {
			moved.add(id);
			out.add((kind: \moved, id: id, text: "% from % to %".format(this.nameOf(b, byB[id]), this.pathText(a, byA[id]), this.pathText(b, byB[id]))))
		} };
		seqA = common.reject { |id| moved.includes(id) };
		seqB = ib[1].select { |id| byA[id].notNil and: { moved.includes(id).not } };
		kept = this.prLongestCommon(seqA, seqB).asSet;
		seqB.do { |id, k|
			if (kept.includes(id).not) {
				var before = seqB[k - 1];
				out.add((kind: \moved, id: id, text: "% is now %".format(this.nameOf(b, byB[id]),
					if (k == 0 or: { before.isNil }) { "first" } { "after " ++ this.nameOf(b, byB[before]) })))
			}
		};
		// controls
		common.do { |id|
			var na = byA[id], nb = byB[id];
			if (na[\kind] == \synth and: { na[\controls].notNil } and: { nb[\controls].notNil }) {
				var da = Dictionary.new, changes = List.new;
				na[\controls].do { |p| da[p[0]] = p[1] };
				nb[\controls].do { |p|
					var old = da[p[0]];
					if (old.notNil and: { this.prDiffer(old, p[1]) }) {
						changes.add("% % → %".format(p[0], SPInspector.valueString(old), SPInspector.valueString(p[1])))
					}
				};
				if (changes.notEmpty) {
					out.add((kind: \control, id: id, text: "%:  %".format(this.nameOf(b, nb), changes.join(",  "))))
				}
			}
		};
		// busses: who is on them, and whether they sound
		this.prBusDiff(a, b).do { |d| out.add(d) };
		// problems
		b[\problems].do { |p| if (a[\problems].any { |q| q[\key] == p[\key] }.not and: { p[\level] != \info }) {
			out.add((kind: \problem, id: p[\id], text: "new: " ++ p[\text]))
		} };
		a[\problems].do { |p| if (b[\problems].any { |q| q[\key] == p[\key] }.not and: { p[\level] != \info }) {
			out.add((kind: \problem, id: p[\id], text: "gone: " ++ p[\text]))
		} };
		^out.array
	}

	*prDiffer {
		|x, y|
		^if (x.isNumber and: { y.isNumber }) { (x - y).abs > (1e-6 * max(1, x.abs)) } { x != y }
	}

	*pathText {
		|snap, node|
		var names = (node[\path] ? []).reject { |id| id == 0 }.collect { |id| snap[\labels][id] ? "group %".format(id) };
		^if (names.isEmpty) { "the root" } { names.join(" › ") }
	}

	// Nodes that came (or went) as changes: one line a node, but several synths
	// of one def in one group are one line with a count, and a key that stays
	// the same while they come and go.
	*prGrouped {
		|ids, byId, snap, kind, word|
		var groups = Dictionary.new, order = List.new;
		ids.do { |id|
			var n = byId[id], key = if (n[\kind] == \synth) { [n[\def], n[\parent]] } { [\group, id] };
			if (groups[key].isNil) { groups[key] = List.new; order.add(key) };
			groups[key].add(n);
		};
		^order.collect { |key|
			var list = groups[key], n = list.first;
			if (list.size > 1) {
				(kind: kind, id: n[\id], gkey: "%_%".format(key[0], key[1]),
					text: "% ×% % %".format(n[\def], list.size, word, this.pathText(snap, n)))
			} {
				(kind: kind, id: n[\id], gkey: if (n[\kind] == \synth) { "%_%".format(key[0], key[1]) },
					text: "% % %".format(this.nameOf(snap, n), word, this.pathText(snap, n)))
			}
		}
	}

	// who is on a bus, by SynthDef: "nt_src, nt_fx ×2"
	*prDefsText {
		|ids, byId|
		var counts = Dictionary.new, order = List.new;
		ids.do { |id|
			var def = byId[id] !? { |n| n[\def] } ? id;
			if (counts[def].isNil) { order.add(def) };
			counts[def] = (counts[def] ? 0) + 1;
		};
		^if (order.isEmpty) { "nothing" } { order.collect { |d| if (counts[d] > 1) { "% ×%".format(d, counts[d]) } { d.asString } }.join(", ") }
	}

	*prDefSet { |ids, byId| ^ids.collect { |id| byId[id] !? { |n| n[\def] } ? id }.asSet }

	// The busses that changed: in use or not, other SynthDefs writing or
	// reading them (more or fewer of the same def is not news), sounding or silent.
	*prBusDiff {
		|a, b|
		var out = List.new, ra = Dictionary.new, rb = Dictionary.new, byA = this.index(a)[0], byB = this.index(b)[0];
		a[\busses].do { |r| ra[[r[\rate], r[\index]]] = r };
		b[\busses].do { |r| rb[[r[\rate], r[\index]]] = r };
		(ra.keys.asArray ++ rb.keys.asArray).asSet.asArray.sort { |x, y| if (x[0] == y[0]) { x[1] < y[1] } { x[0] < y[0] } }.do { |key|
			var x = ra[key], y = rb[key], r = y ? x, parts = List.new;
			var bus = "% bus %%".format(key[0], if (r[\channels] > 1) { "%-%".format(key[1], key[1] + r[\channels] - 1) } { key[1] },
				r[\name] !? { |n| " (" ++ n ++ ")" } ? "");
			case
			{ x.isNil } { parts.add("in use now, written by % and read by %".format(this.prDefsText(y[\writers], byB), this.prDefsText(y[\readers], byB))) }
			{ y.isNil } { parts.add("no longer used") }
			{ true } {
				if (this.prDefSet(x[\writers], byA) != this.prDefSet(y[\writers], byB)) {
					parts.add("written by % → %".format(this.prDefsText(x[\writers], byA), this.prDefsText(y[\writers], byB)))
				};
				if (this.prDefSet(x[\readers], byA) != this.prDefSet(y[\readers], byB)) {
					parts.add("read by % → %".format(this.prDefsText(x[\readers], byA), this.prDefsText(y[\readers], byB)))
				};
				if (key[0] == \audio and: { x[\level].notNil } and: { y[\level].notNil }) {
					var sx = x[\level] >= SPRouting.silence, sy = y[\level] >= SPRouting.silence;
					if (sx != sy) { parts.add(if (sy) { "silent → sounding" } { "sounding → silent" }) };
				};
			};
			if (parts.notEmpty) { out.add((kind: \bus, bus: [key[0], key[1], r[\channels]], text: bus ++ ":  " ++ parts.join(",  "))) };
		};
		^out.array
	}

	// The longest run of ids in the same order in both lists. What the lists
	// share at their start and at their end is in step by itself; only the
	// middle, where they differ, is worked out.
	*prLongestCommon {
		|x, y|
		var head = 0, tail = 0, n, m, table, out = List.new, i, j, mx, my;
		while { head < x.size and: { head < y.size } and: { x[head] == y[head] } } { head = head + 1 };
		while { tail < (x.size - head) and: { tail < (y.size - head) } and: { x[x.size - 1 - tail] == y[y.size - 1 - tail] } } { tail = tail + 1 };
		mx = x.copyRange(head, x.size - 1 - tail);
		my = y.copyRange(head, y.size - 1 - tail);
		if ((x.size - head - tail) <= 0) { mx = [] };
		if ((y.size - head - tail) <= 0) { my = [] };
		n = mx.size; m = my.size;
		case
		{ n == 0 or: { m == 0 } } { }
		// two long middles: the cheap answer, what is at the same place
		{ (n * m) > 60000 } { mx.do { |id, k| if (my[k] == id) { out.add(id) } } }
		{ true } {
			table = Array.fill(n + 1, { Array.fill(m + 1, 0) });
			n.do { |a| m.do { |b|
				table[a + 1][b + 1] = if (mx[a] == my[b]) { table[a][b] + 1 } { max(table[a][b + 1], table[a + 1][b]) }
			} };
			i = n; j = m;
			while { i > 0 and: { j > 0 } } {
				case
				{ mx[i - 1] == my[j - 1] } { out.add(mx[i - 1]); i = i - 1; j = j - 1 }
				{ table[i - 1][j] >= table[i][j - 1] } { i = i - 1 }
				{ true } { j = j - 1 };
			};
			out = out.reverse;
		};
		^x.keep(head) ++ out.asArray ++ x.keep(tail.neg)
	}

	// ------------------------------------------------------------------ text

	*db { |amp| ^if (amp.isNil) { "?" } { if (amp < 0.00001) { "silent" } { amp.ampdb.round(0.1).asString ++ " dB" } } }

	// the snapshot as a page of text: the tree, the busses, the problems
	*text {
		|snap|
		var lines = List.new, walk, c = this.counts(snap);
		lines.add("node tree of server '%' at % (%)".format(snap[\server], snap[\clock], snap[\stamp]));
		lines.add("% synths, % groups%".format(c[1], c[0],
			if ((snap[\unknown] ? 0) > 0) { ", % of them with a SynthDef this sclang does not know".format(snap[\unknown]) } { "" }));
		lines.add("");
		lines.add("THE TREE, in the order the server runs it");
		walk = { |n, depth|
			var pad = String.fill(depth * 2, $ );
			if (n[\kind] == \group) {
				if (depth >= 0) { lines.add(pad ++ "group % %".format(n[\id], snap[\labels][n[\id]] ? "").stripWhiteSpace) };
				n[\children].do { |ch| walk.value(ch, depth + 1) };
			} {
				lines.add(pad ++ "% %%%".format(n[\def], n[\id],
					if (snap[\paused].includes(n[\id])) { "  [paused]" } { "" },
					snap[\names][n[\id]] !? { |x| "  - " ++ x } ? ""));
				n[\controls] !? { |cs|
					if (cs.notEmpty) {
						lines.add(pad ++ "    " ++ cs.collect { |p| "% %".format(p[0], SPInspector.valueString(p[1])) }.join("  "))
					}
				};
			}
		};
		walk.value(snap[\tree], -1);
		lines.add("");
		lines.add("THE BUSSES");
		snap[\busses].do { |r|
			lines.add("% %%  %  written by %  read by %".format(r[\rate],
				if (r[\channels] > 1) { "%-%".format(r[\index], r[\index] + r[\channels] - 1) } { r[\index] },
				r[\name] !? { |n| " (" ++ n ++ ")" } ? "",
				if (r[\rate] == \audio) { this.db(r[\level]) } { "" },
				if (r[\writers].isEmpty) { "nothing" } { r[\writers].asString },
				if (r[\readers].isEmpty) { "nothing" } { r[\readers].asString }))
		};
		lines.add("");
		lines.add("PROBLEMS");
		if (snap[\problems].isEmpty) { lines.add("none found") };
		snap[\problems].do { |p| lines.add("[%] %".format(p[\level], p[\text])) };
		^lines.join("\n")
	}

	// ------------------------------------------------------------------ disk

	// Write a snapshot as tree-<stamp>.snapshot (an archive: load it back
	// with SPSnapshot.load) and tree-<stamp>.txt (to read). Answers the
	// archive's path.
	*save {
		|snap, dir|
		var base;
		dir = dir ? folder;
		File.mkdir(dir);
		base = dir +/+ "tree-" ++ snap[\stamp];
		snap.writeArchive(base ++ ".snapshot");
		File.use(base ++ ".txt", "w", { |f| f.write(this.text(snap)) });
		^base ++ ".snapshot"
	}

	*load { |path| ^Object.readArchive(path) }

	// the snapshots on disk, newest first
	*files { |dir| ^((dir ? folder) +/+ "tree-*.snapshot").pathMatch.sort.reverse }
}
