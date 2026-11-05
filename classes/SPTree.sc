// =====================================================================
// SPTree - the server's node tree, as an outline you can read
//
// What `s.plotTree` shows, made legible: one row per node, indented by
// depth. A group says what it is when it can be known - the root, the
// default group, an Ndef, a SuperDirt orbit, an fx chain - and how many
// synths are inside it; click it to fold it away. A synth shows its
// SynthDef, in a colour of its own (the same def is always the same
// colour), and its controls when you ask for them. Nodes that have just
// appeared are lit for a second, so you can see what an event or a key
// press made.
//
//   SPTree(s);
//   SPTree(s).showControls = true;     // the controls on each synth row
//   SPTree(s).filter = "dirt";         // only the synths whose def says so
//   SPTree(s).interval = 0.25;         // how often it asks the server
//
// Double click a synth to trace it into the post window (/n_trace).
// One per server; ServerPanel's `p` key opens it.
//
// What a group is called comes from the functions in SPTree.labellers,
// each { |server, labels| } putting nodeID -> name into labels. Add your
// own for a system of yours:
//
//   SPTree.labellers.add({ |server, labels| labels[~myGroup.nodeID] = "my group" });
// =====================================================================

SPTree : SPWindow {
	classvar <labellers, <notes, <intervals = #[0.25, 0.5, 1, 2];

	var <interval = 0.5, <showControls = false, <filter = "";
	var view, resp, poller, tree, rows, labels, collapsed, seen, born, offset = 0;
	var intervalMenu, controlsButton, filterField, countText;

	*rowH { ^18 }
	*indent { ^16 }

	*initClass {
		// what a synth is, from its SynthDef's name: [regexp, what], the first
		// that matches wins. Add your own: SPTree.notes.addFirst(["^my_", "mine"])
		notes = List[
			["^stethoscope", "a scope"],
			["^sp_freqscope", "SPFreqScope"],
			["^sp_meter_", "SPMeter"],
			["^sp_.*_levels$", "ServerPanel's meters"],
			["^sp_ampHistory", "ServerPanel's peak history"],
			["^system_freqScope", "the stock freq scope"],
			["^volumeAmpControl", "the server's volume"],
			["^temp__", "a NodeProxy / Ndef"],
			["^dirt_rms", "SuperDirt meter"],
			["^dirt_monitor", "SuperDirt orbit out"],
			["^dirt_", "SuperDirt"],
			["^global_sdgfader", "SuperDirtGUI fader"],
			["^sdgins_", "SuperDirtGUI insert"],
			["^sdgtrack_", "SuperDirtGUI track"],
			["^sdgreturn_", "SuperDirtGUI return"],
			["^sdg", "SuperDirtGUI"],
			["^insight", "Insight"]
		];
		labellers = List[
			// the two every server has
			{ |server, labels|
				labels[0] = "root";
				labels[server.defaultGroup.nodeID] = "default group";
			},
			// Ndefs: each proxy's group
			{ |server, labels|
				\Ndef.asClass !? { |cls|
					cls.all[server.name] !? { |space|
						space.envir.keysValuesDo { |k, px|
							px.group !? { |g| labels[g.nodeID] = "Ndef(\\%)".format(k) }
						}
					}
				}
			},
			// SuperDirt: its group, and an orbit's
			{ |server, labels|
				\SuperDirt.asClass !? { |cls|
					cls.default !? { |dirt|
						if (dirt.server == server) {
							labels[dirt.group] = "SuperDirt";
							dirt.orbits.do { |o| labels[o.group] = "orbit %".format(o.orbitIndex) };
						}
					}
				}
			},
			// ParaEQ's chains of effects
			{ |server, labels|
				\FxChain.asClass !? { |cls|
					cls.all.do { |chain|
						if (chain.server == server) {
							chain.group !? { |g| labels[g.nodeID] = "fx chain %".format(chain.name) }
						}
					}
				}
			}
		];
	}

	// what a SynthDef's name says it is, from notes, or nil
	*noteOf {
		|def|
		var str = def.asString;
		^notes.detect { |pair| str.findRegexp(pair[0]).notEmpty } !? { |pair| pair[1] }
	}

	title { ^"node tree" }
	defaultSize { ^Point(560, 520) }
	runHint { ^"follow the server or hold still (space)" }

	prInit {
		|argServer, argKey|
		collapsed = IdentitySet.new;
		seen = IdentitySet.new;
		born = IdentityDictionary.new;
		labels = IdentityDictionary.new;
		rows = [];
		^super.prInit(argServer, argKey)
	}

	controls {
		^[
			this.prLabel("every"),
			intervalMenu = this.prMenu(intervals.collect { |t| "% s".format(t) }, intervals.indexOfEqual(interval), 60,
				{ |v| this.interval = intervals[v] }, "how often the server is asked"),
			controlsButton = Button()
				.fixedHeight_(20).fixedWidth_(64)
				.canFocus_(false)
				.font_(this.font(9))
				.states_([["controls", SPWidgetBase.colText, SPWidgetBase.colPanel],
					["controls", Color.black, SPWidgetBase.colAccent]])
				.value_(showControls.binaryValue)
				.toolTip_("show each synth's controls")
				.action_({ |b| this.showControls = b.value == 1 }),
			filterField = TextField()
				.font_(this.font(9))
				.fixedHeight_(20).fixedWidth_(90)
				.string_(filter)
				.toolTip_("only the synths whose SynthDef or group contains this")
				.action_({ |t| this.filter = t.string })
				.keyUpAction_({ |t| this.filter = t.string }),
			this.prButton("open all", 56, { collapsed.clear; this.prRebuild }, "unfold every group"),
			countText = StaticText().font_(this.font(9)).stringColor_(SPWidgetBase.colDim).minWidth_(120)
		]
	}

	// ------------------------------------------------------------- settings

	interval_ { |t| interval = t.asFloat.max(0.05); this.prRestart; this.prRefresh }
	showControls_ { |bool| showControls = bool.booleanValue; this.prAsk; this.prRefresh }
	filter_ { |str| filter = (str ? "").asString; offset = 0; this.prRebuild }

	prSyncControls {
		intervalMenu.value = intervals.indexOfEqual(interval) ? intervalMenu.value;
		controlsButton.value = showControls.binaryValue;
		if (filterField.string != filter) { filterField.string = filter };
	}

	// ---------------------------------------------------------------- server

	prStart {
		resp = OSCFunc({ |msg| if (msg[2] == 0) { this.prReply(msg) } }, '/g_queryTree.reply', server.addr);
		poller = fork {
			loop {
				this.prAsk;
				interval.wait;
			}
		};
	}

	prStop {
		|freeAll = false|
		poller !? { poller.stop };
		poller = nil;
		resp !? { resp.free };
		resp = nil;
	}

	prAsk { if (server.serverRunning) { server.sendMsg('/g_queryTree', 0, showControls.binaryValue) } }

	// /g_queryTree.reply: flag, then per node its id and number of children
	// (-1 for a synth), a synth's def, and with the flag its controls
	prReply {
		|msg|
		var i = 2, withControls = msg[1] != 0, parse;
		parse = {
			var id = msg[i], n = msg[i + 1], node;
			i = i + 2;
			if (n >= 0) {
				node = (id: id, kind: \group, children: n.collect { parse.value });
			} {
				node = (id: id, kind: \synth, def: msg[i]);
				i = i + 1;
				if (withControls) {
					var nc = msg[i];
					i = i + 1;
					node[\controls] = nc.collect { var pair = [msg[i], msg[i + 1]]; i = i + 2; pair };
				};
			};
			node
		};
		tree = parse.value;
		this.prLabelNodes;
		this.prNoteBirths;
		this.prRebuild;
	}

	prLabelNodes {
		labels = IdentityDictionary.new;
		labellers.do { |f| try { f.value(server, labels) } };
	}

	// what was not there at the last answer is lit for a second
	prNoteBirths {
		var now = Main.elapsedTime, ids = IdentitySet.new, walk;
		walk = { |node| ids.add(node[\id]); node[\children].do(walk) };
		tree !? { walk.value(tree) };
		if (seen.notEmpty) { ids.do { |id| if (seen.includes(id).not) { born[id] = now } } };
		seen = ids;
		born.keys.copy.do { |id| if ((now - born[id]) > 1 or: { ids.includes(id).not }) { born.removeAt(id) } };
	}

	// --------------------------------------------------------------- the rows

	// the outline: the tree flattened, folded groups closed, the filter applied
	prRebuild {
		var out = List.new, groups = 0, synths = 0, matches, walk, text = filter.toLower;
		matches = { |node|
			if (node[\kind] == \synth) {
				text.isEmpty or: { node[\def].asString.toLower.contains(text) }
			} {
				text.isEmpty
				or: { (labels[node[\id]] ? "").toLower.contains(text) }
				or: { node[\children].any { |c| matches.value(c) } }
			}
		};
		walk = { |node, depth|
			if (node[\kind] == \group) { groups = groups + 1 } { synths = synths + 1 };
			if (depth >= 0 and: { matches.value(node) }) {
				out.add((node: node, depth: depth));
			};
			if (node[\kind] == \group and: { collapsed.includes(node[\id]).not }) {
				node[\children].do { |c| walk.value(c, depth + 1) };
			} {
				// still counted when folded away
				node[\children].do { |c| this.prCount(c).do { |n, k| if (k == 0) { groups = groups + n } { synths = synths + n } } };
			};
		};
		// the root is the frame, not a row
		tree !? { walk.value(tree, -1); groups = groups - 1 };
		rows = out.array;
		{
			if (this.isOpen) {
				countText.string = "% groups  ·  % synths".format(groups, synths);
				this.prRedraw;
			}
		}.defer;
	}

	// [groups, synths] under a node, itself included
	prCount {
		|node|
		var g = 0, s = 0;
		if (node[\kind] == \group) { g = 1 } { s = 1 };
		node[\children].do { |c| var r = this.prCount(c); g = g + r[0]; s = s + r[1] };
		^[g, s]
	}

	// ------------------------------------------------------------------ view

	prBody {
		view = UserView()
			.background_(SPWidgetBase.colOff)
			.drawFunc_({ |v| this.prDraw(v) })
			.mouseOverAction_({ |v, x, y| this.prReadout(this.prHover(v, x, y)) })
			.mouseLeaveAction_({ this.prReadout("") })
			.mouseDownAction_({ |v, x, y, mods, button, clicks| this.prClick(v, x, y, clicks); true })
			.mouseWheelAction_({ |v, x, y, mods, dx, dy| this.prScroll(v, dy); true });
		^view
	}

	prRedraw { view !? { if (view.isClosed.not) { view.refresh } } }

	prVisibleRows { |v| ^(v.bounds.height / this.class.rowH).floor.asInteger.max(1) }

	prScroll {
		|v, dy|
		var maxOffset = (rows.size - this.prVisibleRows(v)).max(0);
		offset = (offset - (dy.sign * dy.abs.max(1).min(5))).round.asInteger.clip(0, maxOffset);
		this.prRedraw;
	}

	prRowAt { |y| ^rows[(y / this.class.rowH).floor.asInteger + offset] }

	prClick {
		|v, x, y, clicks|
		var row = this.prRowAt(y), node;
		if (row.isNil) { ^this };
		node = row[\node];
		if (node[\kind] == \group) {
			if (collapsed.includes(node[\id])) { collapsed.remove(node[\id]) } { collapsed.add(node[\id]) };
			this.prRebuild;
		} {
			if (clicks == 2 and: { server.serverRunning }) { server.sendMsg('/n_trace', node[\id]) };
		}
	}

	// the same def, the same colour: a hue from its name
	prDefColor {
		|def|
		var h = (def.asString.hash.abs % 1000) / 1000;
		^Color.hsv(h, 0.45, 0.95)
	}

	prDraw {
		|v|
		var w = v.bounds.width, h = v.bounds.height, rh = this.class.rowH, ind = this.class.indent;
		var now = Main.elapsedTime, visible = this.prVisibleRows(v), font = this.font(10), bold = this.font(10, true);
		offset = offset.clip(0, (rows.size - visible).max(0));

		if (tree.isNil) {
			this.prText(if (server.serverRunning) { "asking the server..." } { "the server is not running" }, 10, 10);
			^this
		};
		if (rows.isEmpty) { this.prText("nothing matches \"%\"".format(filter), 10, 10) };

		rows.copyRange(offset, (offset + visible).min(rows.size - 1)).do { |row, k|
			var node = row[\node], depth = row[\depth], y = k * rh, x = 8 + (depth * ind);
			var isNew = born[node[\id]].notNil, idStr = node[\id].asString;
			// the depth guides
			Pen.strokeColor = SPWidgetBase.colGrid.copy.alpha_(0.35);
			Pen.width = 1;
			depth.do { |d| var gx = (8 + (d * ind) + 4).round + 0.5; Pen.line(Point(gx, y), Point(gx, y + rh)) };
			Pen.stroke;
			if (node[\kind] == \group) {
				var open = collapsed.includes(node[\id]).not, n = this.prCount(node), name = labels[node[\id]];
				Pen.fillColor = SPWidgetBase.colPanel.copy.alpha_(0.55);
				Pen.fillRect(Rect(x - 2, y + 1, w - x - 2, rh - 2));
				this.prTriangle(x + 4, y + (rh / 2), open);
				Pen.stringAtPoint(name ? "group", Point(x + 14, y + 2), bold,
					if (isNew) { SPWidgetBase.colAccent } { if (name.notNil) { SPWidgetBase.colText } { SPWidgetBase.colDim } });
				this.prText(idStr, x + 18 + (name ? "group").bounds(bold).width, y + 4);
				this.prText(if (n[1] == 1) { "1 synth" } { "% synths".format(n[1]) }, w - 8, y + 4, nil, \right);
			} {
				var def = node[\def].asString, dx = x + 14, tx;
				Pen.fillColor = this.prDefColor(def);
				Pen.fillOval(Rect(x + 1, y + (rh / 2) - 4, 8, 8));
				Pen.stringAtPoint(def, Point(dx, y + 2), font, if (isNew) { SPWidgetBase.colAccent } { SPWidgetBase.colText });
				tx = dx + def.bounds(font).width + 6;
				this.prText(idStr, tx, y + 4);
				// what it is, at the right, where a group has its count
				this.class.noteOf(def) !? { |note| this.prText(note, w - 8, y + 4, nil, \right) };
				node[\controls] !? { |cs|
					var str = cs.collect { |p| "% %".format(p[0], this.prValueString(p[1])) }.join("  ");
					var cx = tx + idStr.bounds(this.font(9)).width + 10;
					var room = w - cx - 16 - (this.class.noteOf(def) !? { |n| n.bounds(this.font(9)).width } ? 0);
					if (room > 30) { this.prText(this.prFit(str, room), cx, y + 4) };
				};
			};
		};

		// where we are, when there is more than fits
		if (rows.size > visible) {
			var barH = (h * visible / rows.size).max(16), barY = (h - barH) * offset / (rows.size - visible).max(1);
			Pen.fillColor = SPWidgetBase.colGrid.copy.alpha_(0.7);
			Pen.fillRect(Rect(w - 4, barY, 3, barH));
		};

		// keep lighting the new ones until their second is up
		born.keys.copy.do { |id| if ((now - born[id]) > 1) { born.removeAt(id) } };
		if (born.notEmpty) { { this.prRedraw }.defer(0.25) };
	}

	prTriangle {
		|x, y, open|
		Pen.fillColor = SPWidgetBase.colDim;
		if (open) {
			Pen.moveTo(Point(x - 4, y - 2)); Pen.lineTo(Point(x + 4, y - 2)); Pen.lineTo(Point(x, y + 3));
		} {
			Pen.moveTo(Point(x - 2, y - 4)); Pen.lineTo(Point(x + 3, y)); Pen.lineTo(Point(x - 2, y + 4));
		};
		Pen.fill;
	}

	prValueString {
		|v|
		^case
		{ v.isKindOf(Symbol) or: { v.isString } } { v.asString }
		{ v.isNumber.not } { v.asString }
		{ v == v.round } { v.asInteger.asString }
		{ v.abs >= 100 } { v.round(1).asString }
		{ true } { v.round(0.001).asString }
	}

	// as much of str as fits in width, with an ellipsis
	prFit {
		|str, width|
		var font = this.font(9), n;
		if (str.bounds(font).width <= width) { ^str };
		n = (str.size * width / str.bounds(font).width).floor.asInteger - 1;
		^str.keep(n.max(0)) ++ "…"
	}

	prHover {
		|v, x, y|
		var row = this.prRowAt(y), node;
		if (row.isNil) { ^"" };
		node = row[\node];
		^if (node[\kind] == \group) {
			"group %%  ·  click to %".format(node[\id], labels[node[\id]] !? { |l| " (" ++ l ++ ")" } ? "",
				if (collapsed.includes(node[\id])) { "open" } { "fold" })
		} {
			"synth %  %%  ·  double click to trace".format(node[\id], node[\def],
				this.class.noteOf(node[\def]) !? { |n| " (" ++ n ++ ")" } ? "")
		}
	}
}
