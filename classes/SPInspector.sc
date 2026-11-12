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
// free, scope the bus it writes, open its source, copy code that reaches
// it, post all of this.
//
// What the SynthDef says (defaults, busses, UGens) is only known when this
// sclang has its SynthDesc - every def made with .add here has one.
//
//   SPTree(s).inspect(1001);       // the window, on that node
//   SPInspector.textOf(SPTree(s).recordOf(1001), s).postln;
// =====================================================================

SPInspector {
	var <tree, <view, canvas, shownKey, record;
	var titleText, subText, whatText, stateText, pauseButton, freeButton, controlCells, signalText, insideText;
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

	*descOf { |def| ^def !? { SynthDescLib.global.at(def.asSymbol) } }

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
		{ record[\paused] == true } { "paused" ++ (record[\pausedSince] !? { |t| " for " ++ this.duration(now - t) } ? "") }
		{ true } {
			"running" ++ (record[\born] !? { |b| ", seen for " ++ this.duration(now - b) } ? "")
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
		key = record !? { [record[\id], record[\kind], record[\gone], this.class.controlRows(record).collect { |r| [r[\label], r[\array]] }] };
		if (key != shownKey) { shownKey = key; freeArmed = false; this.prBuild(record) } { this.prUpdate };
	}

	prHint {
		|widget, str|
		widget.toolTip = str;
		widget.mouseEnterAction = { tree.prReadout(str) };
		^widget
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
		// what has ended can not be traced, paused or freed (a disabled Button looks the same in Qt)
		if (rec[\gone] == true) { buttons = buttons.reject { |b| [pauseButton, freeButton, buttons[0]].includes(b) }; pauseButton = nil };

		io = if (rec[\kind] == \synth) { this.class.ioRows(rec, server) } { [] };
		io.detect { |r| r[\dir] == \out and: { r[\bus].notNil } } !? { |r|
			extra.add(this.prSmallButton("scope out", {
				SPScope(server).index_(r[\bus]).numChannels_(r[\channels]).rate_(r[\rate]).front
			}, "the bus it writes (%), in the scope - what is on it, not only this synth's part".format(r[\bus]), 70))
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
		};
		grid.setColumnStretch(0, 1);
		^View().layout_(grid.margins_(0).spacing_(2))
	}

	// the values, the state and the busses, without building anything
	prUpdate {
		var rows, server = this.server;
		if (record.isNil or: { view.isClosed }) { ^this };
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
		signalText !? {
			var io = this.class.ioRows(record, server);
			signalText.string = if (io.isEmpty) {
				if (this.class.descOf(record[\def]).isNil) { "not known without its SynthDesc" } { "no In or Out" }
			} { io.collect { |r| this.class.ioText(r) }.join("\n") };
		};
	}
}
