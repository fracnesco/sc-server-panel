// =====================================================================
// SPInspector - everything that can be known about one node
//
// The panel at the right of SPTree. Click a node in the tree (or one that
// has ended, under recent) and it says:
//
//   - what it is and who made it, from SPTree.describers: an Ndef, an
//     orbit's global effect, a synth of one SuperDirt event, and whatever
//     a system of yours adds (SuperDirtGUI says which insert, track or
//     fader a synth is, and which file it came from);
//   - where it sits, the groups above it by name;
//   - whether it runs or is paused (paused or resumed since the window
//     opened: the server can not be asked), how long it has lived;
//   - its controls with their values, defaults and the busses mapped to
//     them - a number can be changed there (/n_set);
//   - the busses it reads and writes, with names (SPTree.busNamers:
//     BusManager's, SuperDirt's orbits, the hardware), read from its
//     SynthDef's In and Out UGens and the controls that feed them;
//   - the UGens inside it.
//
// And buttons: trace (/n_trace, into the post window), pause and run,
// free, scope or listen to the bus it writes, see where its sound goes
// (path), open its source, copy code that reaches it, post all of this. A
// control can be watched (w): its value stays in view under the tree.
//
// A bus (click one in the busses view, or in a path) shows who writes it
// and who reads it, in the order they run, its level, and what looks
// wrong with it. A buffer shows its file and the synths that read it.
//
// What the SynthDef says (defaults, busses, UGens) is only known when this
// sclang has its SynthDesc - every def made with .add here has one.
//
//   SPTree(s).inspect(1001);       // the window, on that node
//   SPInspector.textOf(SPTree(s).recordOf(1001), s).postln;
// =====================================================================

SPInspector {
	classvar <descFinders, foundDescs, missedDescs;

	var <tree, <view, canvas, shownKey, record;
	var titleText, subText, whatText, stateText, pauseButton, freeButton, controlCells, signalText, insideText;
	var levelText, listenButton, listenBus, levelsAt = 0;
	var freeArmed = false;

	*new { |tree| ^super.newCopyArgs(tree).init }

	*width { ^340 }

	init {
		view = ScrollView()
			.fixedWidth_(this.class.width)
			.hasHorizontalScroller_(false)
			.background_(SPWidgetBase.colOff);
		this.prBuild(nil);
	}

	server { ^tree.server }

	font { |size = 10, bold = false| ^tree.font(size, bold) }

	// ------------------------------------------------------------ what it knows

	// A def's SynthDesc: from the SynthDescLib, where every def made with .add
	// is, or from whoever else has the SynthDef itself (descFinders: an Ndef
	// or a ProxySpace's proxy keeps the def of each of its synths). What nobody
	// knows is asked for again after three seconds, not at every call.
	*descOf {
		|def|
		var key, desc, now;
		if (def.isNil) { ^nil };
		key = def.asSymbol;
		desc = SynthDescLib.global.at(key);
		if (desc.notNil) { ^desc };
		desc = foundDescs[key];
		if (desc.notNil) { ^desc };
		now = Main.elapsedTime;
		if ((now - (missedDescs[key] ? -100)) < 3) { ^nil };
		descFinders.do { |f| if (desc.isNil) { desc = try { f.value(key) } } };
		if (desc.isNil) {
			missedDescs[key] = now;
			if (missedDescs.size > 2000) { missedDescs.clear };
		} {
			foundDescs[key] = desc;
			if (foundDescs.size > 500) { foundDescs.clear };
		};
		^desc
	}

	*initClass {
		foundDescs = IdentityDictionary.new;
		missedDescs = IdentityDictionary.new;
		descFinders = List[
			// JITLib: a proxy's objects each keep the SynthDef they built
			{ |key|
				var spaces = [], out;
				\Ndef.asClass !? { |cls| spaces = spaces ++ cls.all.values };
				\ProxySpace.asClass !? { |cls|
					spaces = spaces ++ (cls.all !? { |a| a.values } ? []);
					[currentEnvironment, topEnvironment].do { |e| if (e.isKindOf(cls)) { spaces = spaces.add(e) } };
				};
				spaces.do { |space|
					space.envir.do { |px|
						if (out.isNil and: { px.respondsTo(\objects) }) {
							px.objects.do { |obj|
								var sd = obj.tryPerform(\synthDef);
								if (out.isNil and: { sd.notNil } and: { sd.name.asSymbol == key }) { out = sd.asSynthDesc }
							}
						}
					}
				};
				out
			}
		];
	}

	// how many control values a SynthDesc's synth has
	*numControls {
		|desc|
		^(desc.controls ? []).collect { |c| c.index + c.defaultValue.asArray.size }.maxItem ? 0
	}

	// [[name or index, value] ...], in index order, the way /g_queryTree lists them
	*namedValues {
		|desc, values|
		var names = IdentityDictionary.new;
		desc.controls.do { |c| names[c.index] = c.name.asSymbol };
		^values.collect { |v, k| [names[k] ? k, v] }
	}

	// A synth's controls as rows: (label, index, value, default) for one value,
	// (label, index, values, default, array: true) for an array control.
	*controlRows {
		|record|
		var pairs = record[\controls] ? [], desc = this.descOf(record[\def]), vals = pairs.collect(_[1]), rows = [];
		if (desc.notNil and: { desc.controls.notEmpty }) {
			desc.controls.do { |c|
				var size = c.defaultValue.asArray.size;
				if (c.name.asString != "?") {
					rows = rows.add(if (size > 1) {
						(label: c.name.asString, index: c.index, array: true, default: c.defaultValue,
							values: (c.index .. c.index + size - 1).collect { |k| vals[k] })
					} {
						(label: c.name.asString, index: c.index, value: vals[c.index], default: c.defaultValue.asArray[0])
					})
				}
			};
			^rows
		};
		// no SynthDesc: what the server said, array elements (named by index) joined to the name before them
		pairs.do { |p, k|
			if (p[0].isNumber and: { rows.notEmpty }) {
				var last = rows.last;
				if (last[\array] != true) { last[\array] = true; last[\values] = [last[\value]] };
				last[\values] = last[\values].add(p[1]);
			} {
				rows = rows.add((label: p[0].asString, index: k, value: p[1]))
			}
		};
		^rows
	}

	// The busses it reads and writes, from its SynthDef's In and Out UGens:
	// (dir: \in or \out, type, rate, channels, bus, via, name) - bus nil when
	// it is worked out in the graph, or comes from a mapped control
	*ioRows {
		|record, server|
		var desc = this.descOf(record[\def]), rows = [], rowsOf;
		if (desc.isNil) { ^[] };
		rowsOf = { |list, dir|
			list.do { |io|
				var start = io.startingChannel, bus, via, value;
				case
				{ start.isNumber } { bus = start.asInteger }
				{ start.notNil and: { start.asString != "?" } } {
					via = start.asSymbol;
					value = this.controlRows(record).detect { |r| r[\label].asSymbol == via } !? { |r| r[\value] ?? { r[\default] } };
					value = value ?? { desc.controls.detect { |c| c.name.asSymbol == via } !? { |c| c.defaultValue.asArray[0] } };
					if (value.isNumber) { bus = value.asInteger };
				};
				rows = rows.add((dir: dir, type: io.type.name.asString, rate: io.rate, channels: io.numberOfChannels,
					bus: bus, via: via, name: bus !? { SPTree.busName(server, io.rate, bus) }))
			}
		};
		rowsOf.value(desc.inputs ? [], \in);
		rowsOf.value(desc.outputs ? [], \out);
		^rows
	}

	// "SinOsc 2 · EnvGen 1 ...", most first, and how many UGens in all; nil without the def
	*ugenSummary {
		|desc|
		var counts = Dictionary.new, ugens;
		if (desc.isNil or: { desc.def.isNil }) { ^nil };
		ugens = desc.def.children.reject { |u| u.isKindOf(Control) or: { u.isKindOf(AudioControl) } };
		ugens.do { |u| var n = u.class.name; counts[n] = (counts[n] ? 0) + 1 };
		^"% UGens: ".format(ugens.size) ++ counts.asSortedArray.sort { |a, b| a[1] > b[1] }
			.collect { |p| if (p[1] > 1) { "% ×%".format(p[0], p[1]) } { p[0].asString } }.join(", ")
	}

	*valueString {
		|v|
		^case
		{ v.isNil } { "?" }
		{ v.isKindOf(Symbol) or: { v.isString } } { v.asString }
		{ v.isNumber.not } { v.asString }
		{ v == v.round } { v.asInteger.asString }
		{ v.abs >= 100 } { v.round(1).asString }
		{ true } { v.round(0.001).asString }
	}

	*duration {
		|t|
		t = t ? 0;
		^case
		{ t < 1 } { "% ms".format((t * 1000).round.asInteger) }
		{ t < 60 } { "% s".format(t.round(0.1)) }
		{ t < 3600 } { "% min %s".format((t / 60).floor.asInteger, (t % 60).round.asInteger) }
		{ true } { "% h %min".format((t / 3600).floor.asInteger, ((t % 3600) / 60).round.asInteger) }
	}

	// a mapped control's value (c12, a12) in words, or nil for a plain value
	*mappedText {
		|v, server|
		var str, rate, index;
		if (v.isKindOf(Symbol).not) { ^nil };
		str = v.asString;
		if (str.size < 2 or: { "ca".includes(str[0]).not } or: { str.drop(1).every(_.isDecDigit).not }) { ^nil };
		rate = if (str[0] == $c) { \control } { \audio };
		index = str.drop(1).asInteger;
		^"% bus %".format(rate, index) ++ (SPTree.busName(server, rate, index) !? { |n| " (" ++ n ++ ")" } ? "")
	}

	// what to write for the server in code
	*serverCode { |server| ^if (server == Server.default) { "s" } { "Server(%)".format(server.name.asCompileString) } }

	// a line of code that reaches the node
	*codeOf {
		|record, server|
		var s = this.serverCode(server), id = record[\id];
		^if (record[\kind] == \group) {
			"g = Group.basicNew(%, %);   // %".format(s, id, record[\label] ?? { record[\pathText] } ? "")
		} {
			var first = this.controlRows(record).detect { |r| r[\array] != true and: { r[\value].isNumber } };
			var def = record[\def].asString;
			def = if (def.notEmpty and: { def[0].isAlpha } and: { def.every { |c| c.isAlphaNum or: { c == $_ } } }) { "\\" ++ def } { def.asSymbol.asCompileString };
			"x = Synth.basicNew(%, %, %);   // %\n".format(def, s, id, record[\pathText] ? "")
			++ (first !? { "x.set(%, %);".format(first[\label].asSymbol.asCompileString, this.valueString(first[\value])) } ? "")
		}
	}

	// All of it as text, for the post window
	*textOf {
		|record, server|
		var lines = List.new, d, io, inside, now = Main.elapsedTime;
		if (record.isNil) { ^"nothing selected" };
		d = SPTree.describe(server, record);
		if (record[\kind] == \group) {
			lines.add("group % %".format(record[\id], record[\label] ? ""));
		} {
			lines.add("synth % %".format(record[\id], record[\def]));
		};
		d[\name] !? { |n| lines.add("  is: " ++ n) };
		d[\where] !? { |n| lines.add("  where: " ++ n) };
		lines.add("  in: " ++ (record[\pathText] ? "root"));
		d[\text] !? { |n| lines.add("  " ++ n) };
		d[\file] !? { |n| lines.add("  source: " ++ n) };
		lines.add("  " ++ this.stateOf(record, now));
		if (record[\kind] == \group) {
			lines.add("  holds % groups, % synths".format(record[\groups] ? 0, record[\synths] ? 0));
			(record[\defs] ? []).do { |p| lines.add("    % ×%".format(p[0], p[1])) };
		} {
			lines.add("  controls:");
			this.controlRows(record).do { |r|
				lines.add("    % = %%".format(r[\label],
					if (r[\array] == true) { "[" ++ r[\values].collect { |v| this.valueString(v) }.join(", ") ++ "]" } {
						this.mappedText(r[\value], server) ?? { this.valueString(r[\value]) } },
					r[\default] !? { |x| if (r[\array] == true) { "" } { "   (default %)".format(this.valueString(x)) } } ? ""))
			};
			io = this.ioRows(record, server);
			if (io.notEmpty) { lines.add("  busses:"); io.do { |r| lines.add("    " ++ this.ioText(r)) } };
			inside = this.ugenSummary(this.descOf(record[\def]));
			lines.add("  " ++ (inside ? "(its SynthDef is not in this sclang's SynthDescLib)"));
		};
		^lines.join("\n")
	}

	*ioText {
		|r, level|
		^this.prIoText(r) ++ (level !? { |l| "  ·  " ++ if (l.isString) { l } { SPTree.dbText(l) } } ? "")
	}

	*prIoText {
		|r|
		^"% % % ch, %%".format(if (r[\dir] == \in) { "reads" } { "writes" }, r[\rate], r[\channels],
			r[\bus] !? { |b| "bus %".format(b) } ? "a bus worked out in the graph",
			[r[\name], r[\via] !? { |v| "from %".format(v) }].reject(_.isNil).join(", ") !? { |s| if (s.isEmpty) { "" } { " (" ++ s ++ ")" } }
		) ++ "  · " ++ r[\type]
	}

	*stateOf {
		|record, now|
		now = now ? Main.elapsedTime;
		^case
		{ record[\gone] == true } {
			"gone % ago%".format(this.duration(now - record[\ended]),
				record[\born] !? { |b| ", seen for " ++ this.duration(record[\ended] - b) } ? "")
		}
		{ record[\frozen] == true } { if (record[\paused] == true) { "paused, at that moment" } { "running, at that moment" } }
		// to the second: a number that changes faster is not read
		{ record[\paused] == true } { "paused" ++ (record[\pausedSince] !? { |t| " for " ++ this.duration((now - t).round(1).max(1)) } ? "") }
		{ true } {
			"running" ++ (record[\born] !? { |b| ", seen for " ++ this.duration((now - b).round(1).max(1)) } ? "")
		}
	}

	// onto the clipboard, and into the post window
	*copy {
		|str|
		var path;
		str.postln;
		case
		{ thisProcess.platform.name == \osx } { "printf %s % | pbcopy".format(str.shellQuote).unixCmd(postOutput: false) }
		{ thisProcess.platform.name == \windows } {
			path = Platform.defaultTempDir +/+ "sp_clip.txt";
			File.use(path, "w", { |f| f.write(str) });
			"clip < %".format(path.quote).unixCmd(postOutput: false);
		}
		{ true } { "printf %s % | xclip -selection clipboard".format(str.shellQuote).unixCmd(postOutput: false) };
	}

	*openSource {
		|path|
		var doc = \Document.asClass;
		if (doc.notNil) { doc.open(path) } { path.openOS };
	}

	// ------------------------------------------------------------------ the view

	// Show a node's record (nil for nothing). Built again when another node is
	// shown, or its controls change shape; only the values otherwise.
	show {
		|argRecord|
		var key;
		record = argRecord;
		if (view.isClosed) { ^this };
		key = record !? {
			switch(record[\kind],
				\bus, { [\bus, record[\rate], record[\index], record[\channels], record[\writers], record[\readers], record[\problems].collect { |p| p[\key] }] },
				\buffer, { [\buffer, record[\bufnum], record[\users], record[\text]] },
				{ [record[\id], record[\kind], record[\gone], record[\frozen], this.class.controlRows(record).collect { |r| [r[\label], r[\array]] },
					(record[\problems] ? []).collect { |p| p[\key] }, (record[\buffers] ? []).collect { |b| b[1] }] })
		};
		if (key != shownKey) { shownKey = key; freeArmed = false; this.prBuild(record) } { this.prUpdate };
	}

	prHint {
		|widget, str|
		// through the tree's hint bar, which looks the pointer up among its hinted controls
		^tree.prHintAttach(widget, str)
	}

	prHeading {
		|str|
		^StaticText().string_(str).font_(this.font(9, true)).stringColor_(SPWidgetBase.colDim)
	}

	prSmallButton {
		|str, action, hint, width = 58|
		^this.prHint(Button()
			.fixedHeight_(20).fixedWidth_(width)
			.canFocus_(false)
			.font_(this.font(9))
			.states_([[str, SPWidgetBase.colText, SPWidgetBase.colPanel]])
			.action_({ action.value }), hint)
	}

	prBuild {
		|rec|
		var items = List.new, d, server = this.server, buttons = List.new, extra = List.new, desc, io;
		canvas = View().background_(SPWidgetBase.colOff);
		controlCells = [];
		if (rec.isNil) {
			items.add(StaticText().string_("click a node in the tree to see what it is").font_(this.font(10))
				.stringColor_(SPWidgetBase.colDim));
			canvas.layout = VLayout(*(items.array ++ [nil])).margins_(8).spacing_(6);
			view.canvas = canvas;
			^this
		};
		levelText = nil; listenButton = nil; listenBus = nil; signalText = nil; pauseButton = nil; stateText = nil;
		if (rec[\kind] == \bus) { ^this.prBuildBus(rec) };
		if (rec[\kind] == \buffer) { ^this.prBuildBuffer(rec) };
		d = SPTree.describe(server, rec);
		desc = this.class.descOf(rec[\def]);

		titleText = StaticText().font_(this.font(12, true))
			.string_(if (rec[\kind] == \group) { rec[\label] ? "group" } { rec[\def].asString })
			.stringColor_(if (rec[\kind] == \group) { SPWidgetBase.colText } { SPTree.defColor(rec[\def]) });
		subText = StaticText().font_(this.font(9)).stringColor_(SPWidgetBase.colDim)
			.string_("% %  ·  in %".format(rec[\kind], rec[\id], rec[\pathText] !? { |p| if (p.isEmpty) { "root" } { p } } ? "root"));
		items.add(titleText);
		items.add(subText);
		whatText = StaticText().font_(this.font(10)).stringColor_(SPWidgetBase.colText)
			.string_([d[\name], d[\where], d[\text]].reject(_.isNil).join("\n") !? { |s| if (s.isEmpty) { "nothing says what it is" } { s } });
		items.add(whatText);
		stateText = StaticText().font_(this.font(9)).stringColor_(SPWidgetBase.colDim).string_(this.class.stateOf(rec));
		items.add(stateText);
		// what looks wrong with it
		(rec[\problems] ? []).do { |p|
			items.add(StaticText().font_(this.font(9)).string_(p[\text])
				.stringColor_(if (p[\level] == \info) { SPWidgetBase.colDim } { SPWidgetBase.colClip }))
		};

		// the buttons
		if (rec[\kind] == \synth) {
			buttons.add(this.prSmallButton("trace", { server.sendMsg('/n_trace', rec[\id]) },
				"every UGen's inputs and outputs, once, into the post window (/n_trace)"));
		} {
			buttons.add(this.prSmallButton("dump", { server.sendMsg('/g_dumpTree', rec[\id], 1) },
				"this group's nodes and their controls, into the post window (/g_dumpTree)"));
		};
		pauseButton = this.prSmallButton("pause", {
			server.sendMsg('/n_run', rec[\id], if (record[\paused] == true) { 1 } { 0 });
		}, "pause it or let it run again (/n_run) - a SuperDirt effect asleep is paused too, and pauses itself again after silence");
		buttons.add(pauseButton);
		freeButton = this.prSmallButton("free", {
			if (freeArmed) {
				server.sendMsg(if (rec[\kind] == \group) { '/g_freeAll' } { '/n_free' }, rec[\id]);
				freeArmed = false;
			} {
				freeArmed = true;
				freeButton.states = [["free?", Color.black, SPWidgetBase.colAccent]];
				{ freeArmed = false; if (freeButton.isClosed.not) { freeButton.states = [["free", SPWidgetBase.colText, SPWidgetBase.colPanel]] } }.defer(2);
			}
		}, if (rec[\kind] == \group) { "free everything inside this group - click twice" } { "free this synth - click twice" });
		buttons.add(freeButton);
		buttons.add(this.prSmallButton("copy", { this.class.copy(this.class.codeOf(record, server)) },
			"a line of code that reaches it, onto the clipboard and into the post window"));
		buttons.add(this.prSmallButton("post", { this.class.textOf(record, server).postln },
			"all of this, as text, into the post window"));
		// what has ended, or is a kept moment, can not be traced, paused or freed (a disabled Button looks the same in Qt)
		if (rec[\gone] == true or: { rec[\frozen] == true }) {
			buttons = buttons.reject { |b| [pauseButton, freeButton, buttons[0]].includes(b) }; pauseButton = nil
		};

		io = if (rec[\kind] == \synth) { this.class.ioRows(rec, server) } { [] };
		if (rec[\kind] == \synth and: { rec[\gone] != true }) {
			extra.add(this.prSmallButton("path", { tree.showPath(rec[\id]) },
				"where its sound goes, bus by bus, to the outputs - and where it stops", 44));
		};
		io.detect { |r| r[\dir] == \out and: { r[\bus].notNil } } !? { |r|
			extra.add(this.prSmallButton("scope out", {
				SPScope(server).index_(r[\bus]).numChannels_(r[\channels]).rate_(r[\rate]).front
			}, "the bus it writes (%), in the scope - what is on it, not only this synth's part".format(r[\bus]), 70));
			if (r[\rate] == \audio and: { rec[\gone] != true } and: { rec[\frozen] != true }) {
				extra.add(this.prSmallButton("freq", { SPFreqScope(server).inBus_(r[\bus]).front },
					"the bus it writes (%), in the frequency scope".format(r[\bus]), 44));
				listenBus = [r[\bus], r[\channels]];
				listenButton = this.prSmallButton("listen", { tree.listen(r[\bus], r[\channels]) },
					"hear the bus it writes (%) on the outputs, beside what plays there - again to stop".format(r[\bus]), 50);
				extra.add(listenButton);
			};
		};
		d[\file] !? { |path|
			extra.add(this.prSmallButton("source", { this.class.openSource(path) }, "open " ++ path, 58))
		};
		(d[\actions] ? []).do { |a|
			extra.add(this.prSmallButton(a[0], { a[1].value(record, server) }, a[0], (a[0].bounds(this.font(9)).width + 16).max(50)))
		};
		items.add(HLayout(*(buttons.asArray ++ [nil])).spacing_(4));
		extra.clump(4).do { |row| items.add(HLayout(*(row ++ [nil])).spacing_(4)) };

		if (rec[\kind] == \group) {
			items.add(this.prHeading("inside"));
			items.add(StaticText().font_(this.font(9)).stringColor_(SPWidgetBase.colText)
				.string_("% groups, % synths\n".format(rec[\groups] ? 0, rec[\synths] ? 0)
					++ (rec[\defs] ? []).collect { |p| "% ×%".format(p[0], p[1]) }.join("\n")));
		} {
			items.add(this.prHeading("controls"));
			items.add(this.prControlGrid(rec));
			items.add(this.prHeading("busses"));
			signalText = StaticText().font_(this.font(9)).stringColor_(SPWidgetBase.colText);
			items.add(signalText);
			if ((rec[\buffers] ? []).notEmpty) {
				items.add(this.prHeading("buffers"));
				items.add(StaticText().font_(this.font(9)).stringColor_(SPWidgetBase.colText)
					.string_(rec[\buffers].collect { |b| "% = %  %".format(b[0], b[1], b[2] ? "") }.join("\n")));
			};
			items.add(this.prHeading("inside"));
			insideText = StaticText().font_(this.font(9)).stringColor_(SPWidgetBase.colText)
				.string_(this.class.ugenSummary(desc) ? "its SynthDef is not in this sclang's SynthDescLib, so its busses and UGens are not known");
			items.add(insideText);
		};
		canvas.layout = VLayout(*(items.array ++ [nil])).margins_(8).spacing_(6);
		view.canvas = canvas;
		this.prUpdate;
	}

	// a row per control: its name, its value (a box to change it, or the bus
	// mapped to it), its default
	prControlGrid {
		|rec|
		var rows = this.class.controlRows(rec), grid = GridLayout(), server = this.server;
		if (rows.isEmpty) {
			^StaticText().font_(this.font(9)).stringColor_(SPWidgetBase.colDim)
				.string_(if (rec[\gone] == true) { "not seen" } { "asking..." })
		};
		rows.do { |r, k|
			var name = StaticText().string_(r[\label]).font_(this.font(9)).stringColor_(SPWidgetBase.colText);
			var cell, def;
			if (r[\array] == true) {
				cell = StaticText().font_(this.font(9)).stringColor_(SPWidgetBase.colDim);
			} {
				cell = NumberBox()
					.font_(this.font(9)).fixedHeight_(18).fixedWidth_(80)
					.decimals_(4).minDecimals_(0).maxDecimals_(4)
					.step_(0.01).scroll_step_(0.01)
					.background_(SPWidgetBase.colPanel)
					.normalColor_(if (rec[\gone] == true) { SPWidgetBase.colDim } { SPWidgetBase.colText })
					.typingColor_(SPWidgetBase.colAccent)
					.action_({ |b| server.sendMsg('/n_set', rec[\id], r[\index], b.value) });
				if (rec[\gone] == true) { cell.enabled = false };
				this.prHint(cell, "% of synth % - type a value and return to set it (/n_set)".format(r[\label], rec[\id]));
			};
			def = StaticText().font_(this.font(9)).stringColor_(SPWidgetBase.colDim)
				.string_(r[\default] !? { |x| if (r[\array] == true) { "" } { this.class.valueString(x) } } ? "");
			controlCells = controlCells.add([cell, r[\index], r[\array] == true]);
			grid.add(name, k, 0);
			grid.add(cell, k, 1);
			grid.add(def, k, 2);
			if (r[\array] != true and: { rec[\gone] != true } and: { rec[\frozen] != true }) {
				var what = (kind: \control, id: rec[\id], index: r[\index], name: r[\label], def: rec[\def]);
				var on = tree.isWatched(what);
				grid.add(this.prHint(Button()
					.fixedHeight_(16).fixedWidth_(18).canFocus_(false).font_(this.font(8))
					.states_([["w", SPWidgetBase.colDim, SPWidgetBase.colPanel], ["w", Color.black, SPWidgetBase.colAccent]])
					.value_(on.binaryValue)
					.action_({ tree.watchToggle(what) }),
					"watch %: its value stays in view under the tree, with its last moments as a line".format(r[\label])), k, 3);
			};
		};
		grid.setColumnStretch(0, 1);
		^View().layout_(grid.margins_(0).spacing_(2))
	}

	// the values, the state and the busses, without building anything
	prUpdate {
		var rows, server = this.server;
		if (record.isNil or: { view.isClosed }) { ^this };
		if (record[\kind] == \bus) { levelText !? { |t| t.string = this.prBusLevelText(record) }; ^this.prListenState };
		if (record[\kind] == \buffer) { ^this };
		stateText !? { stateText.string = this.class.stateOf(record) };
		pauseButton !? {
			pauseButton.states = [[if (record[\paused] == true) { "run" } { "pause" }, SPWidgetBase.colText, SPWidgetBase.colPanel]]
		};
		if (record[\kind] != \synth) { ^this };
		rows = this.class.controlRows(record);
		controlCells.do { |c|
			var r = rows.detect { |x| x[\index] == c[1] }, cell = c[0];
			r !? {
				if (c[2]) {
					cell.string = r[\values].collect { |v| this.class.valueString(v) }.join(" ")
				} {
					case
					{ r[\value].isNumber } {
						if (cell.isKindOf(NumberBox) and: { cell.hasFocus.not }) { cell.value = r[\value] }
					}
					{ this.class.mappedText(r[\value], server).notNil } {
						if (cell.isKindOf(NumberBox)) { cell.enabled = false; cell.toolTip = this.class.mappedText(r[\value], server) }
					}
				}
			}
		};
		this.prSignal;
		this.prListenState;
	}

	// the busses a synth reads and writes, each with what is on it now
	prSignal {
		var server = this.server, io;
		if (signalText.isNil or: { record.isNil } or: { record[\kind] != \synth }) { ^this };
		io = this.class.ioRows(record, server);
		signalText.string = if (io.isEmpty) {
			if (this.class.descOf(record[\def]).isNil) { "not known without its SynthDesc" } { "no In or Out" }
		} {
			io.collect { |r|
				this.class.ioText(r, if (r[\rate] == \audio and: { r[\bus].notNil } and: { record[\gone] != true }) {
					tree.levelOver(r[\bus], r[\channels]) !? { |l| tree.steadyDb(("i" ++ r[\dir] ++ r[\bus]).asSymbol, l) }
				})
			}.join("\n")
		};
	}

	prListenState {
		listenButton !? { |b|
			if (b.isClosed.not and: { listenBus.notNil }) {
				var on = tree.isListeningTo(listenBus[0]);
				b.states = [["listen", if (on) { Color.black } { SPWidgetBase.colText }, if (on) { SPWidgetBase.colAccent } { SPWidgetBase.colPanel }]];
			}
		}
	}

	// The levels have moved: what shows one is written again, four times a
	// second at most - a number that changes faster is not read.
	levels {
		var now = Main.elapsedTime;
		if (view.isClosed or: { record.isNil } or: { (now - levelsAt) < 0.25 }) { ^this };
		levelsAt = now;
		if (record[\kind] == \bus) {
			levelText !? { |t| if (t.isClosed.not) { t.string = this.prBusLevelText(record) } }
		} { this.prSignal };
	}

	// ------------------------------------------------------------------ a bus

	prBusLevelText {
		|rec|
		^if (rec[\rate] == \audio) {
			tree.levelOver(rec[\index], rec[\channels]) !? { |l| "level now: " ++ tree.steadyDb(("ib" ++ rec[\index]).asSymbol, l) } ? "level: not measured"
		} {
			tree.probe.kval(rec[\index]) !? { |v| "value now: " ++ this.class.valueString(v) } ? "value: not asked for yet"
		}
	}

	prNodeButtons {
		|ids, empty|
		var server = this.server;
		if (ids.isEmpty) {
			^[StaticText().font_(this.font(9)).stringColor_(SPWidgetBase.colDim).string_(empty)]
		};
		^ids.collect { |id|
			var rec = tree.recordOf(id), def = (rec !? { rec[\def] } ? "?").asString;
			var name = rec !? { SPTree.describe(server, rec)[\name] };
			this.prHint(Button()
				.fixedHeight_(18).canFocus_(false).font_(this.font(9))
				.states_([["%  %%".format(def, id, name !? { |n| "  ·  " ++ n } ? ""), SPTree.defColor(def), SPWidgetBase.colPanel]])
				.action_({ tree.inspect(id) }), "show % (%) in the tree".format(def, id))
		}
	}

	prBuildBus {
		|rec|
		var items = List.new, server = this.server, buttons = List.new, what;
		var num = if (rec[\channels] > 1) { "%-%".format(rec[\index], rec[\index] + rec[\channels] - 1) } { rec[\index].asString };
		items.add(StaticText().font_(this.font(12, true)).stringColor_(SPWidgetBase.colMeter)
			.string_("% bus %".format(rec[\rate], num)));
		items.add(StaticText().font_(this.font(9)).stringColor_(SPWidgetBase.colDim)
			.string_("%%".format(rec[\name] !? { |n| n ++ "  ·  " } ? "",
				switch(rec[\busKind], \out, { "a hardware output" }, \in, { "a hardware input" }, \private, { "a private audio bus" }, { "a control bus" }))));
		levelText = StaticText().font_(this.font(10)).stringColor_(SPWidgetBase.colText).string_(this.prBusLevelText(rec));
		items.add(levelText);
		rec[\problems].do { |p|
			items.add(StaticText().font_(this.font(9)).string_(p[\text])
				.stringColor_(if (p[\level] == \info) { SPWidgetBase.colDim } { SPWidgetBase.colClip }))
		};
		if (rec[\rate] == \audio) {
			buttons.add(this.prSmallButton("path", { tree.showPath([rec[\rate], rec[\index], rec[\channels]]) },
				"where what is on this bus goes, to the outputs", 44));
			buttons.add(this.prSmallButton("scope", { SPScope(server).index_(rec[\index]).numChannels_(rec[\channels]).rate_(\audio).front },
				"this bus in the scope", 50));
			buttons.add(this.prSmallButton("freq", { SPFreqScope(server).inBus_(rec[\index]).front }, "this bus in the frequency scope", 44));
			listenBus = [rec[\index], rec[\channels]];
			listenButton = this.prSmallButton("listen", { tree.listen(rec[\index], rec[\channels]) },
				"hear this bus on the outputs, beside what plays there - again to stop", 50);
			buttons.add(listenButton);
		} {
			buttons.add(this.prSmallButton("scope", { SPScope(server).index_(rec[\index]).numChannels_(rec[\channels]).rate_(\control).front },
				"this bus in the scope", 50));
		};
		what = (kind: \bus, rate: rec[\rate], index: rec[\index], name: rec[\name]);
		buttons.add(this.prHint(Button()
			.fixedHeight_(20).fixedWidth_(50).canFocus_(false).font_(this.font(9))
			.states_([["watch", SPWidgetBase.colText, SPWidgetBase.colPanel], ["watch", Color.black, SPWidgetBase.colAccent]])
			.value_(tree.isWatched(what).binaryValue)
			.action_({ tree.watchToggle(what) }), "keep this bus in view under the tree, with its last moments as a line"));
		items.add(HLayout(*(buttons.asArray ++ [nil])).spacing_(4));
		items.add(this.prHeading("written by, in the order they run"));
		this.prNodeButtons(rec[\writers], "nothing in the tree writes it right now").do { |b| items.add(b) };
		items.add(this.prHeading("read by"));
		this.prNodeButtons(rec[\readers], "nothing in the tree reads it").do { |b| items.add(b) };
		canvas.layout = VLayout(*(items.array ++ [nil])).margins_(8).spacing_(6);
		view.canvas = canvas;
		this.prListenState;
	}

	// ------------------------------------------------------------------ a buffer

	prBuildBuffer {
		|rec|
		var items = List.new, buf = rec[\buffer];
		items.add(StaticText().font_(this.font(12, true)).stringColor_(SPWidgetBase.colText).string_("buffer %".format(rec[\bufnum])));
		items.add(StaticText().font_(this.font(10)).stringColor_(SPWidgetBase.colText)
			.string_([rec[\name], buf !? { buf.path }, buf !? {
				"% frames, % ch, % Hz".format(buf.numFrames ? "?", buf.numChannels ? "?", buf.sampleRate ? "?")
			} ?? { "this sclang did not make it: nothing more is known" }].reject(_.isNil).join("\n")));
		buf !? {
			items.add(HLayout(this.prSmallButton("plot", { buf.plot }, "the buffer's contents in a plot window", 44), nil));
		};
		items.add(this.prHeading("read by"));
		this.prNodeButtons(rec[\users], "no synth's controls name it right now").do { |b| items.add(b) };
		canvas.layout = VLayout(*(items.array ++ [nil])).margins_(8).spacing_(6);
		view.canvas = canvas;
	}
}
