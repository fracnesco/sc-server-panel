// =====================================================================
// SPTree - the server's node tree, as an outline you can read
//
// What `s.plotTree` shows, made legible: one row per node, indented by
// depth. A group says what it is when it can be known - the root, the
// default group, an Ndef, a SuperDirt orbit, an fx chain - and how many
// synths are inside it; click its triangle to fold it away. A synth shows
// its SynthDef, in a colour of its own (the same def is always the same
// colour), what it belongs to, and its controls when you ask for them.
// Nodes that have just appeared are lit for a second, so you can see what
// an event or a key press made.
//
// Click a node and the inspector at the right says everything that can
// be known about it (SPInspector): who made it, its controls (change them
// there), the busses it reads and writes, the UGens inside, whether it is
// paused, and buttons to trace, pause, free, scope or open its source.
// recent lists the synths that have ended - a SuperDirt event's synths
// live a fraction of a second, so most of them are caught here.
//
//   SPTree(s);
//   SPTree(s).showControls = true;     // the controls on each synth row
//   SPTree(s).filter = "dirt";         // only the synths whose def says so
//   SPTree(s).interval = 0.25;         // how often it asks the server
//   SPTree(s).inspect(1001);           // select a node and show it
//
// Double click a synth to trace it into the post window (/n_trace).
// One per server; ServerPanel's `p` key opens it.
//
// What a group is called comes from SPTree.labellers, each { |server, labels| }
// putting nodeID -> name into labels; what a node is, in more words, from
// SPTree.describers; what a bus is from SPTree.busNamers. Add your own for
// a system of yours:
//
//   SPTree.labellers.add({ |server, labels| labels[~myGroup.nodeID] = "my group" });
//   SPTree.describers.addFirst({ |server, node|
//       if (node[\id] == ~mine.nodeID) { (name: "my drone", text: "the pad under everything") }
//   });
//   SPTree.busNamers.addFirst({ |server, rate, index| if (index == 20) { "my send" } });
// =====================================================================

SPTree : SPWindow {
	classvar <labellers, <notes, <describers, <busNamers, <intervals = #[0.25, 0.5, 1, 2];
	classvar <>historySize = 300;
	classvar <dirts;		// SuperDirt instances to know about, beside SuperDirt.default and ~dirt

	var <interval = 0.5, <showControls = false, <filter = "", <mode = \tree;
	var <showInspector = true, <>captureEvents = true;
	var view, inspector, resp, nodeResps, poller, tree, rows, labels, collapsed, seen, born, offset = 0;
	var intervalMenu, controlsButton, filterField, countText, recentButton, inspectorButton;
	var byId, known, <history, paused, pending, <selected, selControls, lastAsked;

	*rowH { ^18 }
	*indent { ^16 }

	*initClass {
		// what a synth is, from its SynthDef's name: [regexp, what], the first
		// that matches wins. Add your own: SPTree.notes.addFirst(["^my_", "mine"])
		dirts = List.new;
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
				this.prDirtOf(server) !? { |dirt|
					labels[dirt.group] = "SuperDirt";
					dirt.orbits.do { |o| labels[o.group] = "orbit %".format(o.orbitIndex) };
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
		// What a node is, for the inspector: each { |server, node| } answers an
		// Event or nil. node has id, kind (\synth or \group), def, parent, path
		// (the ids of the groups above it, root first) and controls ([name, value]
		// pairs, when known). The answer may hold
		//   name     what it is, in a few words (also the note at the right of its row)
		//   where    where it sits, in words
		//   text     more, if there is more to say
		//   file     the source file it came from
		//   actions  [[label, { |node, server| }] ...], buttons in the inspector
		// Every describer is asked; for each key the first answer wins, actions add up.
		// A system adds its own with addFirst, so it speaks before these.
		describers = List[
			// an Ndef's synth
			{ |server, node|
				var px = this.prProxyOf(server, node[\parent]);
				px !? {
					(name: "Ndef(\\%)".format(px.key),
						text: "the synth playing Ndef(\\%)'s source, % ch %".format(px.key, px.numChannels ? "?", px.rate ? ""),
						actions: [["post source", { "Ndef(\\%) = %".format(px.key, px.source.asCompileString).postln }]])
				}
			},
			// SuperDirt: a global effect of an orbit, or a synth of one event
			{ |server, node|
				var dirt = this.prDirtOf(server), out;
				dirt !? {
					dirt.orbits.do { |o|
						o.globalEffects.do { |fx|
							if (out.isNil and: { fx.synth.notNil } and: { fx.synth.nodeID == node[\id] }) {
								out = (name: "orbit %'s %".format(o.orbitIndex, fx.name),
									where: "orbit %, after its events".format(o.orbitIndex),
									text: "a global effect of SuperDirt's orbit %: Tidal's parameters set it on every event".format(o.orbitIndex))
							}
						};
						if (out.isNil and: { node[\kind] == \synth } and: { node[\path].size >= 1 }
							and: { node[\path].last != o.group } and: { node[\path].includes(o.group) }) {
							out = (name: "an event on orbit %".format(o.orbitIndex),
								where: "orbit %, the group of one event".format(o.orbitIndex),
								text: "SuperDirt made it for one event from Tidal (or a \\dirt event): it lives as long as the event's sustain")
						}
					};
				};
				out
			},
			// what its def's name says
			{ |server, node| node[\def] !? { |d| this.noteOf(d) !? { |n| (name: n) } } }
		];
		// What a bus is: each { |server, rate, index| } answers a name or nil; the first wins.
		busNamers = List[
			// BusManager's names
			{ |server, rate, index|
				\BusManager.asClass !? { |cls|
					cls.entries.detect { |e|
						e.bus.notNil and: { e.bus.rate == rate } and: { index >= e.bus.index }
						and: { index < (e.bus.index + e.bus.numChannels) }
					} !? { |e| if (index == e.bus.index) { e.name.asString } { "% + %".format(e.name, index - e.bus.index) } }
				}
			},
			// SuperDirt's orbit busses
			{ |server, rate, index|
				var out;
				if (rate == \audio) {
					this.prDirtOf(server) !? { |dirt|
						dirt.orbits.do { |o|
							[[o.dryBus, "dry"], [o.globalEffectBus, "fx"], [o.synthBus, "synth"]].do { |pair|
								var b = pair[0];
								if (out.isNil and: { b.notNil } and: { index >= b.index } and: { index < (b.index + b.numChannels) }) {
									out = "orbit % %".format(o.orbitIndex, pair[1]) ++ if (index > b.index) { " + %".format(index - b.index) } { "" }
								}
							}
						}
					}
				};
				out
			},
			// the hardware
			{ |server, rate, index|
				var outs = server.options.numOutputBusChannels, ins = server.options.numInputBusChannels;
				if (rate == \audio) {
					case
					{ index < outs } { "hardware out %".format(index) }
					{ index < (outs + ins) } { "hardware in %".format(index - outs) }
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

	// What the describers say about a node: an Event, empty when nothing is known
	*describe {
		|server, node|
		var out = (), actions = [];
		describers.do { |f|
			var d = try { f.value(server, node) };
			if (d.isKindOf(Dictionary)) {
				d.keysValuesDo { |k, v| if (k == \actions) { actions = actions ++ v } { if (out[k].isNil) { out[k] = v } } }
			}
		};
		if (actions.notEmpty) { out[\actions] = actions };
		^out
	}

	// a bus in words: its name from busNamers, or nil
	*busName {
		|server, rate, index|
		busNamers.do { |f| (try { f.value(server, rate.asSymbol, index.asInteger) }) !? { |n| ^n } };
		^nil
	}

	// The SuperDirt on a server, or nil: SuperDirt.default, ~dirt, or one a
	// system put into SPTree.dirts (SuperDirt.start sets no default of its own)
	*prDirtOf {
		|server|
		var cls = \SuperDirt.asClass;
		if (cls.isNil) { ^nil };
		^([cls.default, topEnvironment[\dirt], currentEnvironment[\dirt]] ++ dirts.asArray)
			.detect { |d| d.isKindOf(cls) and: { d.server == server } }
	}

	*prProxyOf {
		|server, groupID|
		\Ndef.asClass !? { |cls|
			cls.all[server.name] !? { |space|
				space.envir.do { |px| if (px.group.notNil and: { px.group.nodeID == groupID }) { ^px } }
			}
		};
		^nil
	}

	title { ^"node tree" }
	defaultSize { ^Point(920, 560) }
	runHint { ^"follow the server or hold still (space)" }

	prInit {
		|argServer, argKey|
		collapsed = IdentitySet.new;
		seen = IdentitySet.new;
		born = IdentityDictionary.new;
		labels = IdentityDictionary.new;
		byId = IdentityDictionary.new;
		known = IdentityDictionary.new;
		paused = IdentityDictionary.new;
		pending = IdentityDictionary.new;
		history = List.new;
		rows = [];
		^super.prInit(argServer, argKey)
	}

	controls {
		^[
			this.prLabel("every"),
			intervalMenu = this.prMenu(intervals.collect { |t| "% s".format(t) }, intervals.indexOfEqual(interval), 60,
				{ |v| this.interval = intervals[v] }, "how often the server is asked"),
			controlsButton = this.prToggle("controls", 64, showControls, { |b| this.showControls = b },
				"show each synth's controls on its row"),
			filterField = TextField()
				.font_(this.font(9))
				.fixedHeight_(20).fixedWidth_(90)
				.string_(filter)
				.toolTip_("only the synths whose SynthDef or group contains this")
				.action_({ |t| this.filter = t.string })
				.keyUpAction_({ |t| this.filter = t.string }),
			this.prButton("open all", 56, { collapsed.clear; this.prRebuild }, "unfold every group"),
			recentButton = this.prToggle("recent", 56, mode == \recent, { |b| this.mode = if (b) { \recent } { \tree } },
				"the synths that have ended, newest first - a SuperDirt event's synths live a moment, most are caught here"),
			inspectorButton = this.prToggle("inspector", 64, showInspector, { |b| this.showInspector = b },
				"the panel at the right: everything about the node you clicked"),
			countText = StaticText().font_(this.font(9)).stringColor_(SPWidgetBase.colDim).minWidth_(120)
		]
	}

	prToggle {
		|str, width, value, action, hint|
		^Button()
			.fixedHeight_(20).fixedWidth_(width)
			.canFocus_(false)
			.font_(this.font(9))
			.states_([[str, SPWidgetBase.colText, SPWidgetBase.colPanel],
				[str, Color.black, SPWidgetBase.colAccent]])
			.value_(value.binaryValue)
			.toolTip_(hint ? "")
			.action_({ |b| action.value(b.value == 1) })
	}

	// ------------------------------------------------------------- settings

	interval_ { |t| interval = t.asFloat.max(0.05); this.prRestart; this.prRefresh }
	showControls_ { |bool| showControls = bool.booleanValue; this.prAsk; this.prRefresh }
	filter_ { |str| filter = (str ? "").asString; offset = 0; this.prRebuild }

	// \tree, the nodes running now, or \recent, those that have ended
	mode_ { |m| mode = if (m == \recent) { \recent } { \tree }; offset = 0; this.prRebuild; this.prRefresh }

	showInspector_ {
		|bool|
		showInspector = bool.booleanValue;
		{ inspector !? { |i| if (i.view.isClosed.not) { i.view.visible = showInspector } } }.defer;
		this.prRefresh;
	}

	// Select a node by id (a running one, or one in history) and show it in the inspector.
	select {
		|id|
		selected = id;
		selControls = nil;
		this.prAskSelected;
		this.prShowSelected;
		this.prRedraw;
	}

	// Open the window on a node: select it, unfold the groups above it, show it.
	inspect {
		|id|
		var node = byId[id];
		node !? { node[\path].do { |g| collapsed.remove(g) } };
		if (node.isNil and: { this.recordOf(id).notNil } and: { known[id].isNil }) { this.mode = \recent };
		if (showInspector.not) { this.showInspector = true };
		this.select(id);
		this.prRebuild;
		this.prScrollTo(id);
		this.front;
	}

	prSyncControls {
		intervalMenu.value = intervals.indexOfEqual(interval) ? intervalMenu.value;
		controlsButton.value = showControls.binaryValue;
		recentButton.value = (mode == \recent).binaryValue;
		inspectorButton.value = showInspector.binaryValue;
		if (filterField.string != filter) { filterField.string = filter };
	}

	// ---------------------------------------------------------------- server

	prStart {
		resp = OSCFunc({ |msg| this.prReply(msg) }, '/g_queryTree.reply', server.addr);
		nodeResps = [
			OSCFunc({ |msg| this.prNodeGo(msg) }, '/n_go', server.addr),
			OSCFunc({ |msg| this.prNodeEnd(msg) }, '/n_end', server.addr),
			OSCFunc({ |msg| paused[msg[1]] = Main.elapsedTime; this.prChangedSelected(msg[1]) }, '/n_off', server.addr),
			OSCFunc({ |msg| paused.removeAt(msg[1]); this.prChangedSelected(msg[1]) }, '/n_on', server.addr),
			OSCFunc({ |msg| this.prControlValues(msg) }, '/n_setn', server.addr)
		];
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
		nodeResps.do(_.free);
		nodeResps = nil;
	}

	prAsk { if (server.serverRunning) { server.sendMsg('/g_queryTree', 0, showControls.binaryValue) } }

	// the selected synth's controls: by index through its SynthDesc when this
	// sclang has it (/s_getn), else its group's tree with controls
	prAskSelected {
		var node, desc, total;
		if (server.serverRunning.not or: { selected.isNil }) { ^this };
		node = byId[selected];
		// ended since the last tree (its /n_end came): nothing to ask
		if (node.isNil or: { node[\kind] != \synth } or: { known[selected].isNil }) { ^this };
		desc = SPInspector.descOf(node[\def]);
		total = desc !? { SPInspector.numControls(desc) } ? 0;
		if (total > 0) {
			server.sendMsg('/s_getn', selected, 0, total)
		} {
			node[\parent] !? { |p| pending[p] = node[\path]; server.sendMsg('/g_queryTree', p, 1) }
		};
	}

	// /g_queryTree.reply: flag, then per node its id and number of children
	// (-1 for a synth), a synth's def, and with the flag its controls. The
	// whole tree (asked for group 0) is the outline; a group asked for on its
	// own (a new one, or the selected synth's) only adds what it holds.
	prReply {
		|msg|
		var i = 2, withControls = msg[1] != 0, parse, root = msg[2], sub, path;
		parse = { |parent, path|
			var id = msg[i], n = msg[i + 1], node;
			i = i + 2;
			if (n >= 0) {
				node = (id: id, kind: \group, parent: parent, path: path);
				node[\children] = n.collect { parse.value(id, path ++ [id]) };
			} {
				node = (id: id, kind: \synth, def: msg[i], parent: parent, path: path);
				i = i + 1;
				if (withControls) {
					var nc = msg[i];
					i = i + 1;
					node[\controls] = nc.collect { var pair = [msg[i], msg[i + 1]]; i = i + 2; pair };
				};
			};
			node
		};
		if (root == 0) {
			tree = parse.value(nil, []);
			this.prIndex;
			this.prLabelNodes;
			this.prNoteBirths;
			this.prAbsorb(tree, true);
			this.prRebuild;
			// asked right after the tree, so a synth that has ended is not asked for
			this.prAskSelected;
		} {
			path = pending.removeAt(root) ?? { byId[root] !? { |g| g[\path] } } ? [];
			sub = parse.value(path.last, path);
			this.prAbsorb(sub, false);
		};
		this.prShowSelected;
	}

	// id -> node for the tree just read
	prIndex {
		var walk;
		byId = IdentityDictionary.new;
		walk = { |node| byId[node[\id]] = node; node[\children].do(walk) };
		tree !? { walk.value(tree) };
	}

	// What is known about every synth seen: its def, where it was, its last
	// controls. A synth of the whole tree that is not there any more has ended,
	// and goes into history.
	prAbsorb {
		|node, whole|
		var now = Main.elapsedTime, walk;
		walk = { |n|
			if (n[\kind] == \synth) {
				var r = known[n[\id]];
				if (r.isNil) {
					r = (id: n[\id], kind: \synth, born: now, captured: whole.not);
					known[n[\id]] = r;
				};
				r[\def] = n[\def];
				r[\parent] = n[\parent];
				r[\path] = n[\path];
				r[\pathText] = this.prPathText(n[\path]);
				r[\seen] = now;
				n[\controls] !? { |c| r[\controls] = c; if (n[\id] == selected) { selControls = c } };
			};
			n[\children].do(walk);
		};
		walk.value(node);
		if (whole) {
			known.keys.copy.do { |id|
				if (byId[id].isNil) { this.prEnded(id, now) }
			};
		};
	}

	prEnded {
		|id, time|
		var r = known.removeAt(id);
		r ?? { ^this };
		r[\ended] = time ? Main.elapsedTime;
		paused.removeAt(id);
		history.addFirst(r);
		while { history.size > historySize } { history.pop };
	}

	// a group just made: ask what it holds at once, before its synths end -
	// SuperDirt's event synths have no notifications of their own
	prNodeGo {
		|msg|
		var id = msg[1], parent = msg[2], isGroup = msg[5] == 1;
		if (isGroup and: { captureEvents } and: { running }) {
			pending[id] = (byId[parent] !? { |p| p[\path] ++ [parent] }) ? [parent];
			server.sendMsg('/g_queryTree', id, 1);
		};
	}

	prNodeEnd {
		|msg|
		var id = msg[1];
		if (known[id].notNil) { this.prEnded(id) };
		paused.removeAt(id);
		this.prChangedSelected(id);
	}

	// /n_setn: the answer to /s_getn - nodeID, then index, count, values, repeated
	prControlValues {
		|msg|
		var id = msg[1], node = byId[id], desc, values = Array.newClear(0), k = 2;
		if (id != selected or: { node.isNil }) { ^this };
		desc = SPInspector.descOf(node[\def]);
		desc ?? { ^this };
		values = Array.newClear(SPInspector.numControls(desc));
		while { k < msg.size } {
			var index = msg[k], count = msg[k + 1];
			count.do { |j| if ((index + j) < values.size) { values[index + j] = msg[k + 2 + j] } };
			k = k + 2 + count;
		};
		selControls = SPInspector.namedValues(desc, values);
		known[id] !? { |r| r[\controls] = selControls };
		this.prShowSelected;
	}

	prChangedSelected { |id| if (id == selected) { this.prShowSelected } }

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

	// "orbit 0 › group 1043": the groups above a node, by name when they have one
	prPathText {
		|path|
		^path.reject { |id| id == 0 }.collect { |id| labels[id] ? "group %".format(id) }.join(" › ")
	}

	// ---------------------------------------------------------- the inspector

	// Everything known about a node, running or ended, as an Event: what the
	// tree says, what was seen of it, whether it is paused. Nil if unknown.
	recordOf {
		|id|
		var node = byId[id], r, old;
		old = known[id] ?? { history.detect { |h| h[\id] == id } };
		if (node.isNil) {
			if (old.isNil) { ^nil };
			r = old.copy;
			r[\gone] = old[\ended].notNil;
			r[\paused] = paused[id].notNil;
			^r
		};
		r = node.copy;
		r.removeAt(\children);
		r[\pathText] = this.prPathText(node[\path]);
		r[\label] = labels[id];
		r[\paused] = paused[id].notNil;
		r[\pausedSince] = paused[id];
		old !? { r[\born] = old[\born]; r[\captured] = old[\captured] };
		if (node[\kind] == \synth) {
			r[\controls] = selControls ?? { node[\controls] } ?? { old !? { old[\controls] } };
		} {
			r[\groups] = this.prCount(node)[0] - 1;
			r[\synths] = this.prCount(node)[1];
			r[\defs] = this.prDefsIn(node);
		};
		^r
	}

	// [[def, how many] ...] of the synths under a group, most first
	prDefsIn {
		|node|
		var counts = Dictionary.new, walk;
		walk = { |n| if (n[\kind] == \synth) { counts[n[\def]] = (counts[n[\def]] ? 0) + 1 }; n[\children].do(walk) };
		walk.value(node);
		^counts.asSortedArray.sort { |a, b| a[1] > b[1] }
	}

	prShowSelected {
		var record = selected !? { this.recordOf(selected) };
		{
			if (this.isOpen and: { inspector.notNil }) { inspector.show(record) }
		}.defer;
	}

	// --------------------------------------------------------------- the rows

	// the outline: the tree flattened, folded groups closed, the filter applied;
	// or, in \recent, the ended synths
	prRebuild {
		var out = List.new, groups = 0, synths = 0, matches, walk, text = filter.toLower;
		if (mode == \recent) {
			history.do { |r|
				if (text.isEmpty or: { r[\def].asString.toLower.contains(text) } or: { (r[\pathText] ? "").toLower.contains(text) }) {
					out.add((record: r, depth: 0))
				}
			};
			rows = out.array;
			{ if (this.isOpen) { countText.string = "% ended  ·  newest first".format(history.size); this.prRedraw } }.defer;
			^this
		};
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

	prRowId { |row| ^(row[\node] ?? { row[\record] })[\id] }

	// ------------------------------------------------------------------ view

	prBody {
		view = UserView()
			.background_(SPWidgetBase.colOff)
			.canFocus_(true)
			.drawFunc_({ |v| this.prDraw(v) })
			.mouseOverAction_({ |v, x, y| this.prReadout(this.prHover(v, x, y)) })
			.mouseLeaveAction_({ this.prReadout("") })
			.mouseDownAction_({ |v, x, y, mods, button, clicks| this.prClick(v, x, y, clicks); true })
			.mouseWheelAction_({ |v, x, y, mods, dx, dy| this.prScroll(v, dy); true })
			.keyDownAction_({ |v, char, mods, unicode, keycode, key| this.prKey(key) });
		inspector = SPInspector(this);
		inspector.view.visible = showInspector;
		^View().layout_(HLayout([view, stretch: 1], inspector.view).margins_(0).spacing_(6))
	}

	prRedraw { view !? { if (view.isClosed.not) { view.refresh } } }

	prVisibleRows { |v| ^(v.bounds.height / this.class.rowH).floor.asInteger.max(1) }

	prScroll {
		|v, dy|
		var maxOffset = (rows.size - this.prVisibleRows(v)).max(0);
		offset = (offset - (dy.sign * dy.abs.max(1).min(5))).round.asInteger.clip(0, maxOffset);
		this.prRedraw;
	}

	prScrollTo {
		|id|
		{
			var k = rows.detectIndex { |r| this.prRowId(r) == id }, visible;
			if (k.notNil and: { view.notNil } and: { view.isClosed.not }) {
				visible = this.prVisibleRows(view);
				if (k < offset or: { k >= (offset + visible) }) { offset = (k - (visible div: 2)).max(0) };
				this.prRedraw;
			}
		}.defer(0.1);
	}

	prRowAt { |y| ^rows[(y / this.class.rowH).floor.asInteger + offset] }

	// up and down move the selection; return unfolds or folds a group
	prKey {
		|key|
		var k = rows.detectIndex { |r| this.prRowId(r) == selected };
		case
		{ key == 16r01000013 } { k = (k ? 1) - 1 }		// up
		{ key == 16r01000015 } { k = (k ? -1) + 1 }		// down
		{ key == 16r01000004 } {						// return
			byId[selected] !? { |n| if (n[\kind] == \group) {
				if (collapsed.includes(selected)) { collapsed.remove(selected) } { collapsed.add(selected) };
				this.prRebuild
			} };
			^true
		}
		{ ^nil };
		rows[k.clip(0, rows.size - 1)] !? { |r| this.select(this.prRowId(r)); this.prScrollTo(selected) };
		^true
	}

	prClick {
		|v, x, y, clicks|
		var row = this.prRowAt(y), node, tx;
		view.focus(true);
		if (row.isNil) { ^this };
		if (row[\record].notNil) { ^this.select(row[\record][\id]) };
		node = row[\node];
		tx = 8 + (row[\depth] * this.class.indent);
		if (node[\kind] == \group and: { x < (tx + 12) or: { clicks == 2 } }) {
			// the triangle folds; so does a double click on the row
			if (collapsed.includes(node[\id])) { collapsed.remove(node[\id]) } { collapsed.add(node[\id]) };
			this.prRebuild;
		} {
			this.select(node[\id]);
			if (clicks == 2 and: { node[\kind] == \synth } and: { server.serverRunning }) { server.sendMsg('/n_trace', node[\id]) };
		}
	}

	// the same def, the same colour: a hue from its name
	prDefColor {
		|def|
		var h = (def.asString.hash.abs % 1000) / 1000;
		^Color.hsv(h, 0.45, 0.95)
	}

	*defColor { |def| ^Color.hsv((def.asString.hash.abs % 1000) / 1000, 0.45, 0.95) }

	// what a synth's row says at the right: the describers' name, or the note
	prNoteFor {
		|node|
		^this.class.describe(server, node)[\name]
	}

	prDraw {
		|v|
		var w = v.bounds.width, h = v.bounds.height, rh = this.class.rowH, ind = this.class.indent;
		var now = Main.elapsedTime, visible = this.prVisibleRows(v), font = this.font(10), bold = this.font(10, true);
		offset = offset.clip(0, (rows.size - visible).max(0));

		if (mode == \tree and: { tree.isNil }) {
			this.prText(if (server.serverRunning) { "asking the server..." } { "the server is not running" }, 10, 10);
			^this
		};
		if (rows.isEmpty) {
			this.prText(if (mode == \recent and: { filter.isEmpty }) { "nothing has ended since the window opened" } {
				"nothing matches \"%\"".format(filter) }, 10, 10)
		};

		rows.copyRange(offset, (offset + visible).min(rows.size - 1)).do { |row, k|
			var node = row[\node] ?? { row[\record] }, depth = row[\depth], y = k * rh, x = 8 + (depth * ind);
			var isNew = born[node[\id]].notNil, idStr = node[\id].asString;
			if (node[\id] == selected) {
				Pen.fillColor = SPWidgetBase.colAccent.copy.alpha_(0.22);
				Pen.fillRect(Rect(0, y, w, rh));
			};
			// the depth guides
			Pen.strokeColor = SPWidgetBase.colGrid.copy.alpha_(0.35);
			Pen.width = 1;
			depth.do { |d| var gx = (8 + (d * ind) + 4).round + 0.5; Pen.line(Point(gx, y), Point(gx, y + rh)) };
			Pen.stroke;
			case
			{ row[\record].notNil } {
				// an ended synth: its def, where it was, how long ago
				var def = node[\def].asString, dx = x + 14, tx, ago = now - node[\ended], lived = node[\ended] - node[\born], right, room;
				Pen.fillColor = this.prDefColor(def).copy.alpha_(0.6);
				Pen.fillOval(Rect(x + 1, y + (rh / 2) - 4, 8, 8));
				Pen.stringAtPoint(def, Point(dx, y + 2), font, SPWidgetBase.colDim);
				tx = dx + def.bounds(font).width + 6;
				right = "gone % ago · seen %".format(SPInspector.duration(ago), SPInspector.duration(lived));
				this.prText(right, w - 8, y + 4, nil, \right);
				room = w - 18 - right.bounds(this.font(9)).width - tx;
				if (room > 30) { this.prText(this.prFit(idStr ++ "   " ++ (node[\pathText] ? ""), room), tx, y + 4) };
			}
			{ node[\kind] == \group } {
				var open = collapsed.includes(node[\id]).not, n = this.prCount(node), name = labels[node[\id]];
				Pen.fillColor = SPWidgetBase.colPanel.copy.alpha_(0.55);
				Pen.fillRect(Rect(x - 2, y + 1, w - x - 2, rh - 2));
				this.prTriangle(x + 4, y + (rh / 2), open);
				Pen.stringAtPoint(name ? "group", Point(x + 14, y + 2), bold,
					if (isNew) { SPWidgetBase.colAccent } { if (name.notNil) { SPWidgetBase.colText } { SPWidgetBase.colDim } });
				this.prText(idStr, x + 18 + (name ? "group").bounds(bold).width, y + 4);
				this.prText(if (n[1] == 1) { "1 synth" } { "% synths".format(n[1]) }, w - 8, y + 4, nil, \right);
			}
			{ true } {
				var def = node[\def].asString, dx = x + 14, tx, note = this.prNoteFor(node), isPaused = paused[node[\id]].notNil;
				Pen.fillColor = this.prDefColor(def);
				Pen.fillOval(Rect(x + 1, y + (rh / 2) - 4, 8, 8));
				Pen.stringAtPoint(def, Point(dx, y + 2), font,
					case { isNew } { SPWidgetBase.colAccent } { isPaused } { SPWidgetBase.colDim } { SPWidgetBase.colText });
				tx = dx + def.bounds(font).width + 6;
				this.prText(idStr ++ if (isPaused) { "  paused" } { "" }, tx, y + 4);
				// what it is, at the right, where a group has its count
				note !? { this.prText(note, w - 8, y + 4, nil, \right) };
				node[\controls] !? { |cs|
					var str = cs.collect { |p| "% %".format(p[0], this.prValueString(p[1])) }.join("  ");
					var cx = tx + (idStr ++ if (isPaused) { "  paused" } { "" }).bounds(this.font(9)).width + 10;
					var room = w - cx - 16 - (note !? { |n| n.bounds(this.font(9)).width } ? 0);
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

	prValueString { |v| ^SPInspector.valueString(v) }

	// as much of str as fits in width, with an ellipsis
	prFit {
		|str, width|
		var font = this.font(9), n;
		if (str.bounds(font).width <= width) { ^str };
		n = (str.size * width / str.bounds(font).width).floor.asInteger - 1;
		str = str.keep(n.max(0));
		// never half a character: a String is bytes, and › is three of them
		while { str.notEmpty and: { str.last.ascii < 0 } } { str = str.drop(-1) };
		^str ++ "…"
	}

	prHover {
		|v, x, y|
		var row = this.prRowAt(y), node;
		if (row.isNil) { ^"" };
		if (row[\record].notNil) {
			^"synth %  %  ·  gone % ago (when the tree last lacked it) · click to see what it was".format(row[\record][\id], row[\record][\def],
				SPInspector.duration(Main.elapsedTime - row[\record][\ended]))
		};
		node = row[\node];
		^if (node[\kind] == \group) {
			"group %%  ·  click to inspect, the triangle or a double click to %".format(node[\id],
				labels[node[\id]] !? { |l| " (" ++ l ++ ")" } ? "",
				if (collapsed.includes(node[\id])) { "open" } { "fold" })
		} {
			"synth %  %%  ·  click to inspect, double click to trace".format(node[\id], node[\def],
				this.prNoteFor(node) !? { |n| " (" ++ n ++ ")" } ? "")
		}
	}
}
