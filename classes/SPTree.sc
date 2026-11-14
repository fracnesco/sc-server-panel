// =====================================================================
// SPTree - the server's node tree, as something you can read and debug with
//
// What `s.plotTree` shows, made legible: one row per node, indented by
// depth. A group says what it is when it can be known - the root, the
// default group, an Ndef, a SuperDirt orbit, an fx chain - and how many
// synths are inside it; click its triangle to fold it away. A synth shows
// its SynthDef, in a colour of its own (the same def is always the same
// colour), what it belongs to, a light for what comes out of it, and a red
// mark when something looks wrong with it.
//
// Click a node and the inspector at the right says everything that can
// be known about it (SPInspector). The menu at the top left changes what
// the list shows:
//
//   tree         the nodes, in the order the server runs them
//   busses       every bus in use: who writes it, who reads it, its level
//   problems     what looks wrong: a synth that reads a bus before it is
//                written, synths that pile up, a paused synth left behind,
//                a NaN on a bus, a FAILURE of the server
//   signal path  where the selected synth's sound goes, bus by bus, to the
//                outputs - and where it stops
//   log          every node that started or ended, with the time
//   ended        the synths that have ended, newest first
//   buffers      the buffers, and which synths read them
//   changes      what is different since a snapshot
//
//   SPTree(s);
//   SPTree(s).mode = \busses;
//   SPTree(s).inspect(1001);            // select a node and show it
//   SPTree(s).showPath(1001);           // where its sound goes
//   SPTree(s).snapshot;                 // keep this moment
//   SPTree(s).compare(SPTree(s).snapshots.first);   // what changed since
//   SPTree(s).filter = "dirt";
//
// It stays readable while things change fast (calm, on by default): rows
// are added, taken away and moved at most once every calmTime seconds,
// what you are looking at stays where it is, and a group that makes many
// short-lived nodes - a Tidal pattern's events - shows them as one line.
// The strip under the top row is the last minute and a half; hold the
// window still (space) and click it, or press left and right, to step back.
//
// One per server; ServerPanel's `p` key opens it, and so does s.plotTree.
//
// What a group is called comes from SPTree.labellers, each { |server, labels| }
// putting nodeID -> name into labels; what a node is, in more words, from
// SPTree.describers; what a bus is from SPTree.busNamers; problems only a
// system of yours knows to look for from SPTree.checkers:
//
//   SPTree.labellers.add({ |server, labels| labels[~myGroup.nodeID] = "my group" });
//   SPTree.describers.addFirst({ |server, node|
//       if (node[\id] == ~mine.nodeID) { (name: "my drone", text: "the pad under everything") }
//   });
//   SPTree.busNamers.addFirst({ |server, rate, index| if (index == 20) { "my send" } });
//   SPTree.checkers.add({ |server, model| [(level: \warn, id: 1001, key: "mine", text: "...")] });
// =====================================================================

SPTree : SPWindow {
	classvar <labellers, <notes, <describers, <busNamers, <checkers, <intervals = #[0.25, 0.5, 1, 2];
	classvar <modes = #[\tree, \busses, \problems, \path, \log, \recent, \buffers, \diff];
	classvar <modeNames = #["tree", "busses", "problems", "signal path", "log", "ended", "buffers", "changes"];
	classvar <>historySize = 300;
	classvar <>calmTime = 0.7;		// with calm on, the least time between two layouts of the list
	classvar <>busyRate = 2;		// new nodes a second in one group, from which they are shown as one line
	classvar <>rewind = 90;			// seconds of the server's answers kept to step back through
	classvar <dirts;		// SuperDirt instances to know about, beside SuperDirt.default and ~dirt

	var <interval = 0.5, <showControls = false, <filter = "", <mode = \tree;
	var <showInspector = true, <>captureEvents = true, <calm = true;
	var view, strip, watchView, inspector, resp, nodeResps, poller, tree, rows, labels, collapsed, seen, born, offset = 0;
	var modeMenu, intervalMenu, controlsButton, filterField, countText, calmButton, problemsButton, inspectorButton;
	var byId, known, <history, paused, pending, <selected, selControls, lastAsked;
	var <model, ranges, <problems, <probe, <log, <health, <watch, <snapshots, frames, <viewing, viewTitle;
	var firstSeen, kinds, births, birthRates, busyUntil, recentDefs, expanded, startedAt, firstTree = true, freezeTime;
	var <selBus, <selBuffer, selKey, <pathRoot, pathResult, <diffFrom, <diffTo;
	var layoutAt = 0, layoutDue, visibleRows = 30, drawPending = false, drawAsked = 0, noteCache, outBusses, treeCounts = #[0, 0];
	var lean = false, askedAt, answeredAt = 0, leanCursor = 0, snapWanted = false, problemKeys, fails, shownProblems;
	var dirtBufNames, dirtBufCount, goTimes, askedGroups, fedBefore, dbShown, busLinger, stripBins, bornDue, lastError;

	*rowH { ^18 }
	*indent { ^16 }

	*initClass {
		// what a synth is, from its SynthDef's name: [regexp, what], the first
		// that matches wins. Add your own: SPTree.notes.addFirst(["^my_", "mine"])
		dirts = List.new;
		checkers = List.new;
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
							px.group !? { |g| labels[g.nodeID] = "Ndef(\\" ++ k ++ ")" }
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
					(name: "Ndef(\\" ++ px.key ++ ")",
						text: "the synth playing Ndef(\\" ++ px.key ++ ")'s source, % ch %".format(px.numChannels ? "?", px.rate ? ""),
						actions: [["post source", { ("Ndef(\\" ++ px.key ++ ") = " ++ px.source.asCompileString).postln }]])
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
	defaultSize { ^Point(1040, 620) }
	runHint { ^"follow the server or hold still (space) - held still, the strip under this row steps back through the last minute" }

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
		firstSeen = IdentityDictionary.new;
		kinds = IdentityDictionary.new;
		goTimes = IdentityDictionary.new;
		askedGroups = IdentityDictionary.new;
		births = List.new;
		busyUntil = IdentityDictionary.new;
		recentDefs = IdentityDictionary.new;
		expanded = IdentitySet.new;
		noteCache = IdentityDictionary.new;
		outBusses = IdentityDictionary.new;
		dbShown = Dictionary.new;
		busLinger = IdentityDictionary.new;
		problems = [];
		problemKeys = Set.new;
		fedBefore = Set.new;
		ranges = [];
		frames = List.new;
		snapshots = List.new;
		log = SPLog.new;
		stripBins = IdentityDictionary.new;
		log.onAdd = { |e| this.prStripNote(e) };
		health = SPHealth.new;
		watch = SPWatch.new;
		probe = SPProbe(argServer, { this.prProbed });
		startedAt = Main.elapsedTime;
		^super.prInit(argServer, argKey)
	}

	controls {
		^[
			modeMenu = this.prMenu(modeNames, modes.indexOf(mode) ? 0, 96, { |v| this.mode = modes[v] },
				"what the list shows: the tree, the busses and who is on them, what looks wrong, where a signal goes, the log, the synths that ended, the buffers, what changed since a snapshot"),
			this.prLabel("every"),
			intervalMenu = this.prMenu(intervals.collect { |t| "% s".format(t) }, intervals.indexOfEqual(interval), 60,
				{ |v| this.interval = intervals[v] }, "how often the server is asked"),
			controlsButton = this.prToggle("controls", 60, showControls, { |b| this.showControls = b },
				"show each synth's controls on its row"),
			filterField = TextField()
				.font_(this.font(9))
				.fixedHeight_(20).fixedWidth_(90)
				.string_(filter)
				.toolTip_("only the rows that contain this")
				.action_({ |t| this.filter = t.string })
				.keyUpAction_({ |t| this.filter = t.string }),
			this.prButton("open all", 56, { collapsed.clear; this.prRebuild }, "unfold every group"),
			calmButton = this.prToggle("calm", 44, calm, { |b| this.calm = b },
				"keep the list readable while things change fast: rows move at most once every % s, and a group that makes many short-lived nodes (a pattern's events) shows them as one line".format(calmTime)),
			problemsButton = Button()
				.fixedHeight_(20).fixedWidth_(86)
				.canFocus_(false)
				.font_(this.font(9))
				.states_([["problems", SPWidgetBase.colText, SPWidgetBase.colPanel]])
				.toolTip_("what looks wrong: a synth that reads a bus before it is written, synths that pile up, a NaN on a bus, a FAILURE - click to list them")
				.action_({ this.mode = if (mode == \problems) { \tree } { \problems } }),
			this.prButton("snapshots", 70, { this.prSnapshotMenu },
				"keep the tree as it is at this moment, look at one again, see what changed since, write one to disk"),
			inspectorButton = this.prToggle("inspector", 62, showInspector, { |b| this.showInspector = b },
				"the panel at the right: everything about what you clicked"),
			countText = StaticText().font_(this.font(9)).stringColor_(SPWidgetBase.colDim).minWidth_(150)
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
	showControls_ { |bool| showControls = bool.booleanValue; this.prRebuild; this.prRefresh }
	filter_ { |str| filter = (str ? "").asString; offset = 0; this.prRebuild }
	calm_ { |bool| calm = bool.booleanValue; this.prRebuild; this.prRefresh }

	// What the list shows: \tree the nodes running now, \busses who writes and
	// reads each bus, \problems what looks wrong, \path where the selected
	// node's signal goes, \log what happened, \recent the synths that ended,
	// \buffers, \diff what changed since a snapshot.
	mode_ {
		|m|
		mode = if (modes.includes(m)) { m } { \tree };
		if (mode == \path and: { pathRoot.isNil }) { this.prPathFromSelection };
		offset = 0;
		this.prRebuild;
		this.prRefresh;
	}

	showInspector_ {
		|bool|
		showInspector = bool.booleanValue;
		{ inspector !? { |i| if (i.view.isClosed.not) { i.view.visible = showInspector } } }.defer;
		this.prRefresh;
	}

	// follow the server again: whatever frame or snapshot was shown is left
	running_ {
		|bool|
		if (bool.booleanValue) { viewing = nil; viewTitle = nil } { freezeTime = Main.elapsedTime };
		super.running_(bool);
		this.prRebuild;
	}

	// Select a node by id (a running one, or one in history) and show it in the inspector.
	select {
		|id|
		selected = id;
		selBus = nil;
		selBuffer = nil;
		selKey = id;
		selControls = nil;
		this.prAskSelected;
		this.prProbeSet;
		this.prShowSelected;
		this.prRedraw;
	}

	// Select a bus and show it in the inspector: who writes it, who reads it, its level.
	selectBus {
		|rate, index, channels|
		var r = ranges.detect { |x| x[\rate] == rate and: { index >= x[\index] } and: { index < (x[\index] + x[\channels]) } };
		selected = nil;
		selBuffer = nil;
		selBus = [rate, r !? { r[\index] } ? index, channels ?? { r !? { r[\channels] } } ? 1];
		selKey = this.class.busKey(selBus[0], selBus[1]);
		this.prProbeSet;
		this.prShowSelected;
		this.prRedraw;
	}

	selectBuffer {
		|bufnum|
		selected = nil;
		selBus = nil;
		selBuffer = bufnum;
		selKey = ("b" ++ bufnum).asSymbol;
		this.prShowSelected;
		this.prRedraw;
	}

	*busKey { |rate, index| ^((if (rate == \audio) { "a" } { "k" }) ++ index).asSymbol }

	// Open the window on a node: select it, unfold the groups above it, show it.
	inspect {
		|id|
		var node = byId[id];
		node !? { node[\path].do { |g| collapsed.remove(g) }; node[\parent] !? { |p| expanded.add(p) } };
		if (node.isNil and: { this.recordOf(id).notNil } and: { known[id].isNil }) { mode = \recent } {
			if (node.notNil and: { #[\tree, \path].includes(mode).not }) { mode = \tree }
		};
		if (showInspector.not) { this.showInspector = true };
		this.select(id);
		this.prRebuild;
		this.prRefresh;
		this.prScrollTo(id);
		this.front;
	}

	// Where a node's signal goes: the path view, from a synth or from a bus
	// ([rate, index, channels]). With nothing given, from what is selected.
	showPath {
		|what|
		case
		{ what.isNumber } { pathRoot = (kind: \synth, id: what) }
		{ what.isArray } { pathRoot = (kind: \bus, rate: what[0], index: what[1], channels: what[2] ? 1) }
		{ true } { pathRoot = nil; this.prPathFromSelection };
		mode = \path;
		offset = 0;
		this.prProbeSet;
		this.prRebuild;
		this.prRefresh;
	}

	prPathFromSelection {
		pathRoot = case
		{ selBus.notNil } { (kind: \bus, rate: selBus[0], index: selBus[1], channels: selBus[2]) }
		{ selected.notNil and: { byId[selected] !? { |n| n[\kind] == \synth } ? false } } { (kind: \synth, id: selected) };
	}

	prSyncControls {
		modeMenu.value = modes.indexOf(mode) ? 0;
		intervalMenu.value = intervals.indexOfEqual(interval) ? intervalMenu.value;
		controlsButton.value = showControls.binaryValue;
		calmButton.value = calm.binaryValue;
		inspectorButton.value = showInspector.binaryValue;
		if (filterField.string != filter) { filterField.string = filter };
		this.prSyncProblemsButton;
	}

	prSyncProblemsButton {
		var n = problems.count { |p| p[\level] != \info };
		if (problemsButton.isNil or: { problemsButton.isClosed }) { ^this };
		if (n != shownProblems) {
			shownProblems = n;
			problemsButton.states = [[
				if (n == 0) { "problems" } { "problems  %".format(n) },
				if (n == 0) { SPWidgetBase.colText } { Color.black },
				if (n == 0) { SPWidgetBase.colPanel } { SPWidgetBase.colClip }
			]];
		}
	}

	// ---------------------------------------------------------------- server

	prStart {
		resp = OSCFunc({ |msg| this.prReply(msg) }, '/g_queryTree.reply', server.addr);
		nodeResps = [
			OSCFunc({ |msg| this.prNodeGo(msg) }, '/n_go', server.addr),
			OSCFunc({ |msg| this.prNodeEnd(msg) }, '/n_end', server.addr),
			OSCFunc({ |msg|
				paused[msg[1]] = Main.elapsedTime;
				this.prLogNode(\paused, msg[1]);
				this.prChangedSelected(msg[1])
			}, '/n_off', server.addr),
			OSCFunc({ |msg|
				if (paused.removeAt(msg[1]).notNil) { this.prLogNode(\resumed, msg[1]) };
				this.prChangedSelected(msg[1])
			}, '/n_on', server.addr),
			OSCFunc({ |msg| this.prControlValues(msg) }, '/n_setn', server.addr),
			OSCFunc({ |msg| this.prWatchValue(msg) }, '/n_set', server.addr),
			OSCFunc({ |msg| this.prFail(msg) }, '/fail', server.addr)
		];
		answeredAt = Main.elapsedTime;
		askedAt = nil;
		probe.restart;
		probe.start;
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
		probe.stop;
		if (freeAll) { probe.free };
	}

	// The whole tree with every synth's controls, in one answer. If that
	// answer never comes (a tree too big for one packet), the tree is asked for
	// without them from then on, and the controls synth by synth.
	prAsk {
		var now = Main.elapsedTime;
		if (server.serverRunning.not) { ^this };
		if (lean.not and: { askedAt.notNil } and: { answeredAt < askedAt } and: { (now - askedAt) > max(interval * 3, 2) }) {
			lean = true;
			log.add(\note, (text: "the tree with its controls does not fit one answer of the server: the controls are asked for synth by synth from now on"));
		};
		if (askedAt.isNil or: { answeredAt >= askedAt } or: { lean }) { askedAt = now };
		server.sendMsg('/g_queryTree', 0, if (lean) { 0 } { 1 });
		if (lean) { this.prAskControls };
		// the watched controls, each by its index
		watch.controls.do { |item|
			if (known[item[\id]].notNil) { server.sendMsg('/s_get', item[\id], item[\index]) } { item[\ended] = true }
		};
		probe.poll;
		probe.toTail;
	}

	// lean: forty synths a time, round and round
	prAskControls {
		var order = model !? { model[\order] } ? [], n = order.size, count = 0, k = 0;
		if (n == 0) { ^this };
		while { count < 40 and: { k < n } } {
			var node = order[(leanCursor + k) % n], desc = SPInspector.descOf(node[\def]);
			var total = desc !? { SPInspector.numControls(desc) } ? 0;
			if (total > 0) { server.sendMsg('/s_getn', node[\id], 0, total); count = count + 1 };
			k = k + 1;
		};
		leanCursor = (leanCursor + k) % n;
	}

	// the selected synth's controls: by index through its SynthDesc when this
	// sclang has it (/s_getn), else its group's tree with controls
	prAskSelected {
		var node, desc, total;
		if (server.serverRunning.not or: { selected.isNil } or: { running.not }) { ^this };
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
	// own (a new one, or the selected synth's) only adds what it holds. The
	// window's own probes are left out.
	prReply {
		|msg|
		var i = 2, withControls = msg[1] != 0, parse, root = msg[2], sub, path;
		parse = { |parent, path|
			var id = msg[i], n = msg[i + 1], node;
			i = i + 2;
			if (n >= 0) {
				node = (id: id, kind: \group, parent: parent, path: path);
				node[\children] = n.collect { parse.value(id, path ++ [id]) }.reject(_.isNil);
			} {
				node = (id: id, kind: \synth, def: msg[i], parent: parent, path: path);
				i = i + 1;
				if (withControls) {
					var nc = msg[i];
					i = i + 1;
					node[\controls] = nc.collect { var pair = [msg[i], msg[i + 1]]; i = i + 2; pair };
				};
				if (SPProbe.isOwn(node[\def])) { node = nil };
			};
			node
		};
		if (root == 0) {
			answeredAt = Main.elapsedTime;
			tree = parse.value(nil, []);
			this.prIndex;
			this.prLabelNodes;
			this.prNoteBirths;
			this.prAbsorb(tree, true);
			this.prSafely("reading the busses", { this.prModel });
			this.prSafely("keeping the moment", { this.prFrame });
			if (snapWanted) { snapWanted = false; this.prSafely("the snapshot", { this.prTakeSnapshot }) };
			this.prLayoutSoon;
			// asked right after the tree, so a synth that has ended is not asked for
			this.prAskSelected;
		} {
			path = pending.removeAt(root) ?? { byId[root] !? { |g| g[\path] } } ? [];
			sub = parse.value(path.last, path);
			sub !? { this.prAbsorb(sub, false) };
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
					// the moment the server said it started, when it said so
					r = (id: n[\id], kind: \synth, born: goTimes.removeAt(n[\id]) !? { |g| g[0] } ? now, captured: whole.not);
					known[n[\id]] = r;
					r[\def] = n[\def];
					r[\pathText] = this.prPathText(n[\path]);
					// the first tree is what was there already, not news
					if (firstTree.not) {
						log.add(\born, (id: n[\id], def: n[\def], pathText: r[\pathText], parent: n[\parent]));
						births.add([now, n[\parent]]);
						n[\path].do { |g|
							var d = recentDefs[g];
							if (d.isNil) { d = IdentityDictionary.new; recentDefs[g] = d };
							d[n[\def]] = now;
						};
					};
				};
				r[\def] = n[\def];
				r[\parent] = n[\parent];
				r[\path] = n[\path];
				r[\pathText] = this.prPathText(n[\path]);
				r[\seen] = now;
				n[\controls] !? { |c| r[\controls] = c; if (n[\id] == selected) { selControls = c } };
				// lean: the tree has no controls, what was asked for stands in
				if (n[\controls].isNil and: { whole }) { r[\controls] !? { |c| n[\controls] = c } };
			};
			n[\children].do(walk);
		};
		walk.value(node);
		if (whole) {
			known.keys.copy.do { |id|
				if (byId[id].isNil) { this.prEnded(id, now) }
			};
			firstTree = false;
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
		log.add(\ended, (id: id, def: r[\def], pathText: r[\pathText], parent: r[\parent], lived: r[\ended] - r[\born]));
	}

	// A group just made: ask what it holds at once, before its synths end -
	// SuperDirt's event synths have no notifications of their own. A synth
	// just made: the server says its id and its group, not its SynthDef, so
	// its group is asked for - at most twenty times a second a group, and
	// not a group of more than 64 nodes - to catch it while it lives.
	prNodeGo {
		|msg|
		var id = msg[1], parent = msg[2], isGroup = msg[5] == 1, now = Main.elapsedTime, node;
		if (captureEvents.not or: { running.not }) { ^this };
		if (isGroup) {
			pending[id] = (byId[parent] !? { |p| p[\path] ++ [parent] }) ? [parent];
			server.sendMsg('/g_queryTree', id, 1);
		} {
			if (known[id].isNil and: { byId[id].isNil }) {
				goTimes[id] = [now, parent];
				node = byId[parent];
				// (not once the tree is too big for one answer: a group's would overflow too)
				if (lean.not and: { node.notNil } and: { node[\children].size <= 64 } and: { (now - (askedGroups[parent] ? 0)) >= 0.05 }) {
					askedGroups[parent] = now;
					pending[parent] = node[\path];
					server.sendMsg('/g_queryTree', parent, 1);
				};
			}
		};
	}

	prNodeEnd {
		|msg|
		var id = msg[1], go = goTimes.removeAt(id), now = Main.elapsedTime;
		if (known[id].notNil) { this.prEnded(id) } {
			// it came and went between two looks: when and where is known, what it was is not
			if (go.notNil and: { byId[id].isNil }) {
				births.add([go[0], go[1]]);
				log.add(\ended, (id: id, nodeKind: \synth, parent: go[1], lived: now - go[0],
					pathText: this.prPathText((byId[go[1]] !? { |p| p[\path] } ? []) ++ [go[1]]),
					text: "ended before its SynthDef could be read"));
			}
		};
		paused.removeAt(id);
		this.prChangedSelected(id);
	}

	// /n_setn: the answer to /s_getn - nodeID, then index, count, values, repeated
	prControlValues {
		|msg|
		var id = msg[1], node = byId[id], desc, values, k = 2, named;
		if (node.isNil) { ^this };
		desc = SPInspector.descOf(node[\def]);
		desc ?? { ^this };
		values = Array.newClear(SPInspector.numControls(desc));
		while { k < msg.size } {
			var index = msg[k], count = msg[k + 1];
			if (index.isNumber) {
				count.do { |j| if ((index + j) < values.size) { values[index + j] = msg[k + 2 + j] } };
			};
			k = k + 2 + count;
		};
		named = SPInspector.namedValues(desc, values);
		known[id] !? { |r| r[\controls] = named };
		if (lean) { node[\controls] = named };
		if (id == selected) { selControls = named; this.prShowSelected };
	}

	// /n_set: the answer to /s_get - nodeID, index, value
	prWatchValue {
		|msg|
		var id = msg[1];
		msg[2..].pairsDo { |index, value|
			if (index.isNumber) { watch.put(SPWatch.keyOf((kind: \control, id: id, index: index.asInteger)), value) }
		};
	}

	// a FAILURE of the server: into the log and the problems, with the node it names
	prFail {
		|msg|
		var text = msg[1..].collect(_.asString).join(" "), found = text.findRegexp("[Nn]ode (-?[0-9]+)"), id;
		// the server's own word that the tree with its controls is too big for one answer
		if (text.contains("g_queryTree") and: { text.contains("overflow") }) {
			if (lean.not) {
				lean = true;
				log.add(\note, (text: "the tree with its controls does not fit one answer of the server: the controls are asked for synth by synth from now on"));
			};
			^this
		};
		if (found.size > 1) { id = found[1][1].asInteger };
		log.add(\fail, (id: id, def: id !? { (byId[id] ?? { this.recordOf(id) }) !? { |n| n[\def] } }, text: text));
		fails = fails.add((time: Main.elapsedTime, text: text, id: id)).keep(-20);
	}

	prLogNode {
		|kind, id|
		var n = byId[id];
		log.add(kind, (id: id, def: n !? { n[\def] }, nodeKind: n !? { n[\kind] }, label: labels[id],
			pathText: n !? { this.prPathText(n[\path]) }));
	}

	prChangedSelected { |id| if (id == selected) { this.prShowSelected } }

	prLabelNodes {
		labels = IdentityDictionary.new;
		labellers.do { |f| try { f.value(server, labels) } };
	}

	// What was not there at the last answer is lit for a second, and noted:
	// when it was first seen, and that its parent made something.
	prNoteBirths {
		var now = Main.elapsedTime, ids = IdentitySet.new, walk;
		walk = { |node| ids.add(node[\id]); node[\children].do(walk) };
		tree !? { walk.value(tree) };
		ids.do { |id|
			if (firstSeen[id].isNil) {
				var node = byId[id];
				firstSeen[id] = if (firstTree) { startedAt - 1000 } { now };
				kinds[id] = node[\kind];
				if (firstTree.not) {
					born[id] = now;
					if (node[\kind] == \group) {
						births.add([now, node[\parent]]);
						log.add(\born, (id: id, nodeKind: \group, label: labels[id], pathText: this.prPathText(node[\path]), parent: node[\parent]));
					};
				};
			}
		};
		firstSeen.keys.copy.do { |id|
			if (ids.includes(id).not) {
				if (kinds[id] == \group) { log.add(\ended, (id: id, nodeKind: \group, lived: now - firstSeen[id].max(startedAt))) };
				firstSeen.removeAt(id);
				kinds.removeAt(id);
			}
		};
		seen = ids;
		born.keys.copy.do { |id| if ((now - born[id]) > 1 or: { ids.includes(id).not }) { born.removeAt(id) } };
	}

	// "orbit 0 › group 1043": the groups above a node, by name when they have one
	prPathText {
		|path|
		^(path ? []).reject { |id| id == 0 }.collect { |id| labels[id] ? "group %".format(id) }.join(" › ")
	}

	// ----------------------------------------------------- the busses, the problems

	// the tree as busses, what looks wrong in it, and what the probes measure
	prModel {
		var list = List.new, now = Main.elapsedTime, keys;
		model = SPRouting.build(server, tree, { |id| known[id] !? { |r| r[\controls] } });
		ranges = SPRouting.ranges(model);
		outBusses = IdentityDictionary.new;
		model[\ios].keysValuesDo { |id, ios|
			var chans = [];
			ios.do { |io| if (io[\dir] == \out and: { io[\rate] == \audio } and: { io[\bus].notNil }) {
				io[\channels].do { |c| chans = chans.add(io[\bus] + c) }
			} };
			if (chans.notEmpty) { outBusses[id] = chans };
		};
		this.prProbeSet;
		if (viewing.isNil) {
			list.addAll(SPRouting.problems(model, fedBefore));
			list.addAll(health.check(model[\order], known, history, paused, model[\ios], now));
			// what a system of its own knows to check
			checkers.do { |f| (try { f.value(server, model) } ? []).do { |p| if (p.isKindOf(Dictionary)) { list.add(p) } } };
			// a NaN, an inf or a denormal on a bus
			ranges.do { |r|
				if (r[\rate] == \audio) {
					var bad = r[\channels].collect { |c| probe.bad(r[\index] + c) }.maxItem ? 0;
					if (bad > 0) {
						list.add((level: \warn, kind: \bad, bus: [\audio, r[\index]], id: r[\writers].last !? { |w| w[\id] },
							key: [\bad, r[\index]].asCompileString,
							text: "% carries % - written by %".format(SPRouting.busText(server, \audio, r[\index], r[\channels], r[\name]),
								#["", "a NaN (not a number)", "an inf", "denormals"][bad.clip(0, 3)],
								if (r[\writers].isEmpty) { "nothing in the tree" } { SPRouting.whoText(model, r[\w].asSet.asArray.sort) })))
					}
				}
			};
			// the server's FAILUREs of the last half minute
			(fails ? []).do { |f|
				if ((now - f[\time]) < 30) {
					list.add((level: \warn, kind: \fail, id: f[\id], key: [\fail, f[\time]].asCompileString,
						text: "the server said FAILURE % (% ago)".format(f[\text], SPInspector.duration(now - f[\time]))))
				}
			};
			problems = list.array;
			// what came and what went, into the log
			keys = problems.select { |p| p[\level] != \info and: { p[\kind] != \fail } }.collect { |p| p[\key] }.asSet;
			problems.do { |p| if (p[\level] != \info and: { p[\kind] != \fail } and: { problemKeys.includes(p[\key]).not }) {
				log.add(\problem, (id: p[\id], text: p[\text], level: p[\level]))
			} };
			problemKeys.do { |k| if (keys.includes(k).not) { log.add(\cleared, (text: "a problem went away: " ++ k)) } };
			problemKeys = keys;
		};
	}

	// Which bus channels the probes read: what is watched, selected or on the
	// path first, then the outputs, then every bus of the tree, as many as fit.
	prProbeSet {
		var first = List.new, all, kfirst = List.new;
		if (model.isNil) { ^this };
		watch.busses.do { |item| if (item[\rate] == \audio) { first.add(item[\index]) } { kfirst.add(item[\index]) } };
		selBus !? { |b| if (b[0] == \audio) { b[2].do { |c| first.add(b[1] + c) } } { b[2].do { |c| kfirst.add(b[1] + c) } } };
		selected !? { |id| (outBusses[id] ? []).do { |c| first.add(c) } };
		(pathResult !? { |p| p[\rows] } ? []).do { |r| if (r[\step] == \bus) { r[\channels].do { |c| first.add(r[\index] + c) } } };
		server.options.numOutputBusChannels.do { |c| first.add(c) };
		all = first.asArray ++ model[\audio].keys.asArray.sort;
		probe.setBusses(this.prUnique(all), this.prUnique(kfirst.asArray ++ model[\control].keys.asArray.sort));
	}

	prUnique {
		|list|
		var seenSet = IdentitySet.new;
		^list.select { |x| if (seenSet.includes(x)) { false } { seenSet.add(x); true } }
	}

	// a bus channel's level now, or in the frame being looked at; nil when not measured
	levelOf {
		|index|
		^if (viewing.notNil) { viewing[\levels] !? { |l| l[index] } } { probe.level(index) }
	}

	// the highest level over a run of channels, nil when none is measured
	levelOver {
		|index, channels = 1|
		var vals = channels.collect { |c| this.levelOf(index + c) }.reject(_.isNil);
		^if (vals.isEmpty) { nil } { vals.maxItem }
	}

	// what a synth puts out: the highest level on the busses it writes
	outLevel {
		|id|
		var chans = outBusses[id], vals;
		if (chans.isNil) { ^nil };
		vals = chans.collect { |c| this.levelOf(c) }.reject(_.isNil);
		^if (vals.isEmpty) { nil } { vals.maxItem }
	}

	isBad { |id| ^viewing.isNil and: { (outBusses[id] ? []).any { |c| probe.bad(c) > 0 } } }

	pausedIds { ^if (viewing.notNil) { (viewing[\paused] ? []).asArray } { paused.keys.asArray } }

	problemsOf { |id| ^problems.select { |p| p[\id] == id } }

	problemsOfBus {
		|rate, index, channels = 1|
		^problems.select { |p| p[\bus].notNil and: { p[\bus][0] == rate } and: { p[\bus][1] >= index } and: { p[\bus][1] < (index + channels) } }
	}

	iosOf { |id| ^model !? { model[\ios][id] } }

	// the probes answered: draw the levels, at most ten times a second
	prProbed {
		var now = Main.elapsedTime;
		watch.busses.do { |item|
			var v = if (item[\rate] == \audio) { probe.raw(item[\index]) } { probe.kval(item[\index]) };
			v !? { watch.put(item[\key], v) };
		};
		if (drawPending.not or: { (now - drawAsked) > 1 }) {
			drawPending = true;
			drawAsked = now;
			{
				drawPending = false;
				if (this.isOpen) {
					view.refresh;
					if (watch.isEmpty.not) { watchView.refresh };
					inspector.levels;
				}
			}.defer(0.1);
		};
	}

	// --------------------------------------------------------------- the frames

	// every answer of the server is kept for a minute and a half: held still,
	// the window can step back through them
	prFrame {
		var now = Main.elapsedTime;
		frames.add((time: now, tree: tree, labels: labels, levels: probe.levelsCopy, paused: paused.keys.asArray, problems: problems));
		while { frames.size > 1 and: { (now - frames[0][\time]) > rewind or: { frames.size > 400 } } } { frames.removeAt(0) };
	}

	// Look at a kept moment: a frame of the last minute, or a snapshot. The
	// window holds still until run is pressed.
	prView {
		|frame, title|
		if (running) { this.running = false };
		viewing = frame;
		viewTitle = title;
		tree = frame[\tree];
		labels = frame[\labels] ? labels;
		problems = frame[\problems] ? [];
		this.prIndex;
		this.prModel;
		this.prRebuild;
		this.prShowSelected;
		this.prRefresh;
	}

	// the frame nearest a time
	prFrameAt {
		|time|
		var best;
		frames.do { |f| if (best.isNil or: { (f[\time] - time).abs < (best[\time] - time).abs }) { best = f } };
		^best
	}

	// step through the frames while held still: -1 back, 1 forward
	stepFrame {
		|by|
		var k;
		if (frames.isEmpty) { ^this };
		k = frames.indexOf(viewing) ?? { frames.size - 1 };
		k = (k + by).clip(0, frames.size - 1);
		this.prView(frames[k], nil);
	}

	// --------------------------------------------------------------- snapshots

	// Keep the tree as it is now: every node with its controls, the busses and
	// their levels, the problems. Answers nothing at once when it runs: the
	// snapshot is made from the server's next answer, and is then the first of
	// `snapshots`.
	snapshot {
		if (running and: { server.serverRunning }) { snapWanted = true } { this.prTakeSnapshot };
	}

	prTakeSnapshot {
		var snap;
		if (tree.isNil) { ^nil };
		snap = this.prSnapshotNow;
		snapshots.addFirst(snap);
		while { snapshots.size > 12 } { snapshots.pop };
		log.add(\snapshot, (text: SPSnapshot.title(snap)));
		this.prRefresh;
		^snap
	}

	prSnapshotNow {
		var names = IdentityDictionary.new, levels = IdentityDictionary.new;
		byId.keysValuesDo { |id, n| if (n[\kind] == \synth) { this.prNoteFor(n) !? { |x| names[id] = x } } };
		model[\audio].keysDo { |index| this.levelOf(index) !? { |l| levels[index] = l } };
		^SPSnapshot.make(server, tree, labels, names, model, levels, problems, this.pausedIds)
	}

	// look at a snapshot: the tree as it was, held still
	showSnapshot {
		|snap|
		this.prView((time: snap[\elapsed], tree: snap[\tree], labels: snap[\labels], levels: snap[\levels],
			paused: snap[\paused], problems: snap[\problems]), "snapshot of " ++ snap[\clock]);
	}

	// What changed from one snapshot to another; to nil is the tree as it is now.
	compare {
		|from, to|
		diffFrom = from;
		diffTo = to;
		mode = \diff;
		offset = 0;
		this.prRebuild;
		this.prRefresh;
	}

	// write the newest snapshot (or the one given) to disk; answers its path
	saveSnapshot {
		|snap|
		var path;
		snap = snap ?? { snapshots.first } ?? { this.prTakeSnapshot };
		snap ?? { ^nil };
		path = SPSnapshot.save(snap);
		"SPTree: snapshot written to % (and .txt beside it)".format(path).postln;
		^path
	}

	saveLog {
		var path = SPSnapshot.folder +/+ "log-" ++ Date.getDate.stamp ++ ".txt";
		File.mkdir(SPSnapshot.folder);
		File.use(path, "w", { |f| f.write(log.text) });
		"SPTree: log written to %".format(path).postln;
		^path
	}

	prSnapshotMenu {
		var menu = \Menu.asClass, action = \MenuAction.asClass, items = List.new;
		if (menu.isNil or: { action.isNil }) { ^this.snapshot };
		items.add(action.new("take a snapshot now", { this.snapshot }));
		if (snapshots.notEmpty) {
			items.add(action.separator("look at"));
			snapshots.do { |s| items.add(action.new(SPSnapshot.title(s), { this.showSnapshot(s) })) };
			items.add(action.separator("what changed since"));
			snapshots.do { |s| items.add(action.new(SPSnapshot.title(s), { this.compare(s, nil) })) };
			if (snapshots.size > 1) {
				items.add(action.new("between the last two", { this.compare(snapshots[1], snapshots[0]) }));
			};
			items.add(action.separator("disk"));
			items.add(action.new("write the newest to disk", { this.saveSnapshot }));
		} {
			items.add(action.separator("disk"));
		};
		items.add(action.new("write the log to disk", { this.saveLog }));
		items.add(action.new("read a snapshot from disk...", {
			File.mkdir(SPSnapshot.folder);
			Dialog.openPanel({ |path|
				var s = try { SPSnapshot.load(path) };
				if (s.isKindOf(Dictionary) and: { s[\tree].notNil }) { snapshots.addFirst(s); this.showSnapshot(s) } {
					"SPTree: % is not a snapshot".format(path).warn
				}
			}, path: SPSnapshot.folder)
		}));
		items.add(action.new("open the folder", { File.mkdir(SPSnapshot.folder); SPSnapshot.folder.openOS }));
		menu.new(*items.asArray).front;
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
			r[\gone] = old[\ended].notNil or: { viewing.notNil };
			r[\paused] = paused[id].notNil;
			^r
		};
		r = node.copy;
		r.removeAt(\children);
		r[\pathText] = this.prPathText(node[\path]);
		r[\label] = labels[id];
		r[\paused] = this.pausedIds.includes(id);
		r[\pausedSince] = paused[id];
		r[\frozen] = viewing.notNil;
		old !? { r[\born] = old[\born]; r[\captured] = old[\captured] };
		if (node[\kind] == \synth) {
			r[\controls] = if (viewing.notNil) { node[\controls] } { selControls ?? { node[\controls] } ?? { old !? { old[\controls] } } };
			r[\problems] = this.problemsOf(id);
			r[\buffers] = SPRouting.buffersOf(node[\def], r[\controls]).collect { |p| [p[0], p[1], this.bufferText(p[1])] };
		} {
			r[\groups] = this.prCount(node)[0] - 1;
			r[\synths] = this.prCount(node)[1];
			r[\defs] = this.prDefsIn(node);
		};
		^r
	}

	// a bus as a record for the inspector
	busRecord {
		|rate, index, channels|
		var r = ranges.detect { |x| x[\rate] == rate and: { index >= x[\index] } and: { index < (x[\index] + x[\channels]) } };
		^(kind: \bus, rate: rate, index: r !? { r[\index] } ? index, channels: r !? { r[\channels] } ? channels ? 1,
			busKind: SPRouting.busKind(server, rate, index), name: SPTree.busName(server, rate, index),
			writers: r !? { r[\w].asSet.asArray.sort { |a, b| model[\pos][a] < model[\pos][b] } } ? [],
			readers: r !? { r[\r].asSet.asArray.sort { |a, b| model[\pos][a] < model[\pos][b] } } ? [],
			problems: this.problemsOfBus(rate, r !? { r[\index] } ? index, r !? { r[\channels] } ? channels ? 1))
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
		var record = case
		{ selBus.notNil and: { model.notNil } } { this.busRecord(selBus[0], selBus[1], selBus[2]) }
		{ selBuffer.notNil } { this.bufferRecord(selBuffer) }
		{ selected.notNil } { this.recordOf(selected) };
		{
			if (this.isOpen and: { inspector.notNil }) { inspector.show(record) }
		}.defer;
	}

	// ---------------------------------------------------------------- buffers

	// bufnum -> "bd:3" for the buffers of a SuperDirt on this server
	prDirtBufNames {
		var dirt = this.class.prDirtOf(server), lib, size;
		if (dirt.isNil) { ^nil };
		lib = dirt.soundLibrary.buffers;
		size = lib.size;
		if (dirtBufNames.isNil or: { dirtBufCount != size }) {
			dirtBufNames = IdentityDictionary.new;
			dirtBufCount = size;
			lib.keysValuesDo { |name, list|
				list.do { |buf, k| if (buf.isKindOf(Buffer) and: { buf.bufnum.notNil }) { dirtBufNames[buf.bufnum] = "%:%".format(name, k) } }
			};
		};
		^dirtBufNames
	}

	// "bd:3 · BT0A0A7.wav · 0.31 s · 1 ch", or nil for a buffer this sclang did not make
	bufferText {
		|bufnum|
		var buf = Buffer.cachedBufferAt(server, bufnum), name = this.prDirtBufNames !? { |d| d[bufnum] };
		if (buf.isNil) { ^name };
		^[name, buf.path !? { |p| p.asString.basename },
			if (buf.numFrames.notNil and: { buf.sampleRate.notNil } and: { buf.sampleRate > 0 }) { SPInspector.duration(buf.numFrames / buf.sampleRate) },
			buf.numChannels !? { |c| "% ch".format(c) }].reject(_.isNil).join(" · ")
	}

	// bufnum -> the ids of the synths whose controls name it
	prBufferUsers {
		var users = IdentityDictionary.new;
		(model !? { model[\order] } ? []).do { |n|
			SPRouting.buffersOf(n[\def], n[\controls]).do { |p| users[p[1]] = (users[p[1]] ? []).add(n[\id]) }
		};
		^users
	}

	bufferRecord {
		|bufnum|
		var buf = Buffer.cachedBufferAt(server, bufnum);
		^(kind: \buffer, bufnum: bufnum, buffer: buf, name: this.prDirtBufNames !? { |d| d[bufnum] },
			text: this.bufferText(bufnum), users: this.prBufferUsers[bufnum] ? [])
	}

	// One part failing must not stop the rest, nor fill the post window: the
	// error is posted once every ten seconds, and nil is the answer.
	prSafely {
		|what, func|
		var out, now;
		try { out = func.value } { |err|
			now = Main.elapsedTime;
			if ((now - (lastError ? -100)) > 10) {
				lastError = now;
				"SPTree: % failed: %".format(what, err.errorString).warn;
				err.reportError;
			};
			out = nil;
		};
		^out
	}

	// --------------------------------------------------------------- the rows

	// The list is laid out again - rows added, taken away, moved - at most
	// once every calmTime seconds while calm is on, however fast the server
	// changes. In between, what the rows show (levels, counts, values) is
	// drawn as it changes, in place.
	prLayoutSoon {
		var now = Main.elapsedTime, wait;
		if (calm.not) { ^this.prRebuild };
		wait = (layoutAt + calmTime) - now;
		if (wait <= 0) { ^this.prRebuild };
		// cmd + period clears the clock and would leave this waiting for ever
		if (layoutDue.isNil or: { now > (layoutDue + 1) }) {
			layoutDue = now + wait;
			AppClock.sched(wait, { layoutDue = nil; if (this.isOpen) { this.prRebuild }; nil });
		}
	}

	// Lay the list out now. What was at the top of the view (or the selected
	// row, when it is in view) stays where it is: rows that come and go above
	// it do not push it around.
	prRebuild {
		var anchor, screen, k, count = "";
		layoutAt = Main.elapsedTime;
		noteCache = IdentityDictionary.new;
		if (rows.notEmpty) {
			k = rows.detectIndex { |r| r[\key] == selKey };
			if (k.isNil or: { k < offset } or: { k >= (offset + visibleRows) }) { k = offset.clip(0, rows.size - 1) };
			// a list that grows at its top stays at its top
			if (#[\log, \recent].includes(mode).not or: { offset > 0 }) { anchor = rows[k][\key]; screen = k - offset };
		};
		rows = this.prSafely("laying out " ++ mode, {
			switch(mode,
				\tree, { this.prTreeRows },
				\busses, { this.prBusRows },
				\problems, { this.prProblemRows },
				\path, { this.prPathRows },
				\log, { this.prLogRows },
				\recent, { this.prRecentRows },
				\buffers, { this.prBufferRows },
				\diff, { this.prDiffRows },
				{ [] })
		}) ?? { [this.prHeader("this view could not be laid out: see the post window")] };
		if (anchor.notNil) {
			k = rows.detectIndex { |r| r[\key] == anchor };
			if (k.notNil) { offset = (k - screen).max(0) };
		};
		count = this.prCountText;
		{
			if (this.isOpen) {
				countText.string = count;
				this.prSyncProblemsButton;
				this.prRedraw;
				strip.refresh;
			}
		}.defer;
	}

	prCountText {
		var held = case
		{ viewTitle.notNil } { viewTitle ++ "  ·  " }
		{ viewing.notNil } { "% ago  ·  ".format(SPInspector.duration(freezeTime - viewing[\time])) }
		{ running.not } { "held still  ·  " }
		{ true } { "" };
		^held ++ switch(mode,
			\tree, { "% groups  ·  % synths".format(treeCounts[0], treeCounts[1]) },
			\busses, { "% busses in use".format(ranges.size) },
			\problems, { "% to look at, % notes".format(problems.count { |p| p[\level] != \info }, problems.count { |p| p[\level] == \info }) },
			\path, { if (pathRoot.isNil) { "nothing chosen" } { "where it goes" } },
			\log, { "% lines%".format(log.size, if (offset > 0) { "  ·  held here, newer above" } { "  ·  newest first" }) },
			\recent, { "% ended  ·  newest first".format(history.size) },
			\buffers, { "% buffers".format(rows.count { |r| r[\type] == \buffer }) },
			\diff, { "% changes".format(rows.count { |r| r[\type] == \diff }) },
			{ "" })
	}

	// which groups make many short-lived nodes right now: a pattern's events
	prBusy {
		|now|
		var counts = IdentityDictionary.new, lately = IdentityDictionary.new;
		births.removeAllSuchThat { |b| (now - b[0]) > 3 };
		births.do { |b| b[1] !? { |p|
			counts[p] = (counts[p] ? 0) + 1;
			if ((now - b[0]) <= 1.5) { lately[p] = (lately[p] ? 0) + 1 };
		} };
		// judged on the last second and a half, so a pattern is one line from its first layout
		lately.keysValuesDo { |p, n| if ((n / 1.5) >= busyRate) { busyUntil[p] = now + 5 } };
		birthRates = counts;
	}

	prIsBusy { |id, now| ^(busyUntil[id] ? 0) > now }

	// made since the window opened, and less than ten seconds ago
	prIsTransient {
		|node, now|
		var t = firstSeen[node[\id]];
		^t.notNil and: { t > startedAt } and: { (now - t) < 10 }
	}

	// The outline: the tree flattened, folded groups closed, the filter
	// applied. With calm on, the short-lived children of a busy group are one
	// row (click it to have them one by one).
	prTreeRows {
		var out = List.new, groups = 0, synths = 0, matches, walk, countIn, text = filter.toLower, now = Main.elapsedTime;
		if (viewing.isNil) { this.prBusy(now) };
		matches = { |node|
			if (node[\kind] == \synth) {
				text.isEmpty or: { node[\def].asString.toLower.contains(text) }
				or: { (this.prNoteFor(node) ? "").toLower.contains(text) }
			} {
				text.isEmpty
				or: { (labels[node[\id]] ? "").toLower.contains(text) }
				or: { node[\children].any { |c| matches.value(c) } }
			}
		};
		countIn = { |node| this.prCount(node).do { |n, k| if (k == 0) { groups = groups + n } { synths = synths + n } } };
		walk = { |node, depth|
			var busy, hidden;
			if (node[\kind] == \group) { groups = groups + 1 } { synths = synths + 1 };
			if (depth >= 0 and: { matches.value(node) }) {
				out.add((type: \node, key: node[\id], node: node, depth: depth,
					count: if (node[\kind] == \group) { this.prSteadyCount(node, now) }));
			};
			if (node[\kind] == \group and: { collapsed.includes(node[\id]).not }) {
				busy = calm and: { viewing.isNil } and: { text.isEmpty } and: { this.prIsBusy(node[\id], now) };
				if (busy) {
					hidden = if (expanded.includes(node[\id])) { [] } { node[\children].select { |c| this.prIsTransient(c, now) } };
					out.add((type: \events, key: ("e" ++ node[\id]).asSymbol, parent: node[\id], depth: depth + 1,
						text: this.prEventsText(node, hidden.size, now), open: expanded.includes(node[\id])));
				} { hidden = [] };
				node[\children].do { |c|
					if (hidden.includes(c)) { countIn.value(c) } { walk.value(c, depth + 1) }
				};
			} {
				// still counted when folded away
				node[\children].do { |c| countIn.value(c) };
			};
		};
		// the root is the frame, not a row
		tree !? { walk.value(tree, -1); groups = groups - 1 };
		treeCounts = [groups.max(0), synths];
		^out.array
	}

	// How many synths a group holds, as its row says it: with calm on, the
	// short-lived ones of a busy group are left out of the number (it would
	// change ten times a second) and said in words. [count, some are left out]
	prSteadyCount {
		|node, now|
		var n = 0, plus = false, walk;
		walk = { |g|
			var busy = calm and: { viewing.isNil } and: { this.prIsBusy(g[\id], now) };
			g[\children].do { |c|
				if (busy and: { this.prIsTransient(c, now) }) { plus = true } {
					if (c[\kind] == \synth) { n = n + 1 } { walk.value(c) }
				}
			};
		};
		walk.value(node);
		^[n, plus or: { calm and: { viewing.isNil } and: { this.prIsBusy(node[\id], now) } }]
	}

	// what a busy group's one row says: how many are there, how fast they come, what they are
	prEventsText {
		|node, playing, now|
		var rate = ((birthRates !? { |b| b[node[\id]] } ? 0) / 3).round(1).asInteger;
		var defs = (recentDefs[node[\id]] ? IdentityDictionary.new);
		var names;
		defs.keys.copy.do { |d| if ((now - defs[d]) > 6) { defs.removeAt(d) } };
		names = defs.keys.asArray.collect(_.asString).sort;
		^"short-lived nodes: % here now, about % a second%".format(playing, rate,
			if (names.isEmpty) { "" } { "  ·  " ++ names.keep(8).join(", ") ++ if (names.size > 8) { " ..." } { "" } })
	}

	prHeader { |text| ^(type: \header, key: ("h" ++ text).asSymbol, text: text) }

	prMatches { |str| ^filter.isEmpty or: { str.toLower.contains(filter.toLower) } }

	// The busses in use: hardware first, then the private ones, then control.
	// With calm on, a bus that was in use a moment ago keeps its row for five
	// seconds, dimmed: a bus only a pattern's events write would come and go.
	prBusRows {
		var out = List.new, last, now = Main.elapsedTime, all = ranges, keys = IdentitySet.new;
		if (calm and: { viewing.isNil }) {
			ranges.do { |r| var k = this.class.busKey(r[\rate], r[\index]); keys.add(k); busLinger[k] = [r, now] };
			busLinger.keys.copy.do { |k|
				if (keys.includes(k).not) {
					var e = busLinger[k];
					// not when its channels are part of another row now
					if ((now - e[1]) > 5 or: { ranges.any { |r| r[\rate] == e[0][\rate] and: { e[0][\index] >= r[\index] } and: { e[0][\index] < (r[\index] + r[\channels]) } } }) {
						busLinger.removeAt(k)
					} {
						all = all.add(e[0].copy.put(\idle, true));
					}
				}
			};
			all = all.copy.sort { |a, b| if (a[\rate] == b[\rate]) { a[\index] < b[\index] } { a[\rate] == \audio } };
		};
		all.do { |r|
			var section = switch(r[\kind], \out, { "hardware outputs" }, \in, { "hardware inputs" }, \private, { "private audio busses" }, { "control busses" });
			var names = (r[\w] ++ r[\r]).collect { |id| model[\nodes][id] !? { |n| n[\def].asString } ? "" }.join(" ");
			if (this.prMatches("% % % %".format(r[\rate], r[\index], r[\name] ? "", names))) {
				if (section != last) { out.add(this.prHeader(section)); last = section };
				out.add((type: \bus, key: this.class.busKey(r[\rate], r[\index]), range: r, idle: r[\idle] == true));
			}
		};
		if ((model !? { model[\unknown] } ? 0) > 0) {
			out.add(this.prHeader("% synths have a SynthDef this sclang does not know: their busses are not here".format(model[\unknown])));
		};
		^out.array
	}

	prProblemRows {
		var order = #[\warn, \info], out = List.new, list;
		list = problems.select { |p| this.prMatches(p[\text]) }.copy.sort { |a, b|
			var x = order.indexOf(a[\level]) ? 0, y = order.indexOf(b[\level]) ? 0;
			if (x == y) { a[\key].asString < b[\key].asString } { x < y }
		};
		if (list.any { |p| p[\level] != \info }) { out.add(this.prHeader("to look at")) };
		list.do { |p|
			if (p[\level] == \info and: { out.any { |r| r[\text] == "notes" }.not }) { out.add(this.prHeader("notes")) };
			out.add((type: \problem, key: ("p" ++ p[\key]).asSymbol, problem: p));
		};
		^out.array
	}

	prLogRows {
		^log.rows(calm, filter).collect { |e| (type: \log, key: ("l" ++ e[\serial]).asSymbol, entry: e) }
	}

	prRecentRows {
		var out = List.new, text = filter.toLower;
		history.do { |r|
			if (text.isEmpty or: { r[\def].asString.toLower.contains(text) } or: { (r[\pathText] ? "").toLower.contains(text) }) {
				out.add((type: \record, key: r[\id], record: r, depth: 0))
			}
		};
		^out.array
	}

	// where the chosen node's signal goes, and what feeds it
	prPathRows {
		var out = List.new, result, pausedSet, feeds;
		if (pathRoot.isNil or: { model.isNil }) {
			^[this.prHeader("select a synth or a bus, then choose signal path: where its sound goes, and where it stops")]
		};
		if (pathRoot[\kind] == \synth and: { model[\nodes][pathRoot[\id]].isNil }) {
			^[this.prHeader("synth % is not in this tree any more".format(pathRoot[\id]))]
		};
		pausedSet = IdentitySet.new;
		this.pausedIds.do { |id| pausedSet.add(id) };
		result = SPRouting.pathFrom(model, pathRoot, { |index| this.levelOf(index) }, pausedSet);
		pathResult = result;
		out.add(this.prHeader("where " ++ if (pathRoot[\kind] == \synth) { SPRouting.nodeText(model, pathRoot[\id]) } {
			SPRouting.busText(server, pathRoot[\rate], pathRoot[\index], pathRoot[\channels]) } ++ " goes"));
		result[\verdicts].do { |v, k| out.add((type: \verdict, key: ("v" ++ k).asSymbol, ok: v[\ok], side: v[\side] == true, text: v[\text])) };
		if (result[\verdicts].isEmpty) { out.add((type: \verdict, key: \v0, ok: false, text: "it goes nowhere that can be followed")) };
		if (pathRoot[\kind] == \synth) {
			feeds = SPRouting.feedsOf(model, pathRoot[\id]);
			if (feeds.notEmpty) { out.add(this.prHeader("what feeds it")) };
			feeds.do { |f, k|
				out.add((type: \step, key: ("f" ++ k).asSymbol, step: (step: \bus, depth: 0, rate: f[\rate], index: f[\index], channels: f[\channels],
					level: if (f[\rate] == \audio) { this.levelOver(f[\index], f[\channels]) }, feed: true,
					from: f[\writers])))
			};
		};
		out.add(this.prHeader("the way out, step by step"));
		result[\rows].do { |s, k| out.add((type: \step, key: ("s" ++ k).asSymbol, step: s)) };
		if (result[\truncated]) { out.add(this.prHeader("... and more: the path is cut here")) };
		^out.array
	}

	prBufferRows {
		var out = List.new, users = this.prBufferUsers, names = this.prDirtBufNames, list = List.new, bytes = 0;
		Buffer.cachedBuffersDo(server, { |buf|
			if (buf.bufnum.notNil) {
				var text = this.bufferText(buf.bufnum) ? "";
				bytes = bytes + ((buf.numFrames ? 0) * (buf.numChannels ? 1) * 4);
				if (this.prMatches("% %".format(buf.bufnum, text))) {
					list.add((type: \buffer, key: ("b" ++ buf.bufnum).asSymbol, bufnum: buf.bufnum, text: text, users: users[buf.bufnum] ? []))
				}
			}
		});
		// a buffer a synth names that this sclang did not make
		users.keysValuesDo { |n, ids|
			if (Buffer.cachedBufferAt(server, n).isNil and: { this.prMatches(n.asString) }) {
				list.add((type: \buffer, key: ("b" ++ n).asSymbol, bufnum: n, text: "not made by this sclang", users: ids))
			}
		};
		list = list.asArray.sort { |a, b| if (a[\users].isEmpty == b[\users].isEmpty) { a[\bufnum] < b[\bufnum] } { a[\users].notEmpty } };
		out.add(this.prHeader("% buffers this sclang made, % MB  ·  % in use by a synth right now".format(
			list.size, (bytes / 1048576).round(0.1), list.count { |r| r[\users].notEmpty })));
		^out.array ++ list
	}

	prDiffRows {
		var to, changes, out = List.new, last, names;
		if (diffFrom.isNil) { ^[this.prHeader("take a snapshot (snapshots, at the top), then choose what changed since")] };
		if (diffTo.isNil and: { tree.isNil }) { ^[this.prHeader("no tree yet")] };
		to = diffTo ?? { this.prSnapshotNow };
		changes = SPSnapshot.diff(diffFrom, to);
		names = (added: "added", removed: "removed", moved: "moved", control: "controls changed", bus: "busses", problem: "problems");
		out.add(this.prHeader("from % to %".format(diffFrom[\clock], if (diffTo.isNil) { "now" } { diffTo[\clock] })));
		if (changes.isEmpty) { out.add(this.prHeader("nothing changed")) };
		changes.do { |c, k|
			if (this.prMatches(c[\text])) {
				if (c[\kind] != last) { out.add(this.prHeader(names[c[\kind]] ? c[\kind].asString)); last = c[\kind] };
				// a change's row is the same row while the change stays
				out.add((type: \diff, key: ("d" ++ c[\kind] ++ (c[\gkey] ?? { c[\id] } ?? { c[\bus] } ? k)).asSymbol, change: c));
			}
		};
		^out.array
	}

	// [groups, synths] under a node, itself included
	prCount {
		|node|
		var g = 0, s = 0;
		if (node[\kind] == \group) { g = 1 } { s = 1 };
		node[\children].do { |c| var r = this.prCount(c); g = g + r[0]; s = s + r[1] };
		^[g, s]
	}

	prRowId { |row| ^row[\key] }

	// ------------------------------------------------------------------ view

	prBody {
		var left;
		view = UserView()
			.background_(SPWidgetBase.colOff)
			.canFocus_(true)
			.drawFunc_({ |v| this.prDraw(v) })
			.mouseOverAction_({ |v, x, y| this.prReadout(this.prHover(v, x, y)) })
			.mouseLeaveAction_({ this.prReadout("") })
			.mouseDownAction_({ |v, x, y, mods, button, clicks| this.prClick(v, x, y, clicks); true })
			.mouseWheelAction_({ |v, x, y, mods, dx, dy| this.prScroll(v, dy); true })
			.keyDownAction_({ |v, char, mods, unicode, keycode, key| this.prKey(key) });
		// the last minute and a half: what started and ended when; held still, a place to step back to
		strip = UserView()
			.background_(SPWidgetBase.colOff)
			.fixedHeight_(30)
			.drawFunc_({ |v| this.prDrawStrip(v) })
			.mouseOverAction_({ |v, x, y| this.prReadout(this.prStripHover(v, x)) })
			.mouseLeaveAction_({ this.prReadout("") })
			.mouseDownAction_({ |v, x, y, mods, button, clicks| this.prStripClick(v, x, clicks); true })
			.mouseMoveAction_({ |v, x, y| this.prStripClick(v, x, 1); true });
		// what is watched: a line each, under the list
		watchView = UserView()
			.background_(SPWidgetBase.colOff)
			.fixedHeight_(1)
			.drawFunc_({ |v| this.prDrawWatch(v) })
			.mouseOverAction_({ |v, x, y| this.prReadout("a watched value, with its last moments as a line - click the x to stop watching, anywhere else to select it") })
			.mouseDownAction_({ |v, x, y| this.prWatchClick(x, y); true });
		watchView.visible = false;
		inspector = SPInspector(this);
		inspector.view.visible = showInspector;
		left = View().layout_(VLayout(strip, [view, stretch: 1], watchView).margins_(0).spacing_(4));
		^View().layout_(HLayout([left, stretch: 1], inspector.view).margins_(0).spacing_(6))
	}

	prRedraw {
		view !? { if (view.isClosed.not) { view.refresh; if (watch.isEmpty.not) { watchView.refresh } } }
	}

	prVisibleRows { |v| visibleRows = (v.bounds.height / this.class.rowH).floor.asInteger.max(1); ^visibleRows }

	prScroll {
		|v, dy|
		var maxOffset = (rows.size - this.prVisibleRows(v)).max(0), was = offset;
		offset = (offset - (dy.sign * dy.abs.max(1).min(5))).round.asInteger.clip(0, maxOffset);
		this.prRedraw;
		if ((was == 0) != (offset == 0) and: { mode == \log }) { countText.string = this.prCountText };
	}

	prScrollTo {
		|key|
		{
			var k = rows.detectIndex { |r| r[\key] == key }, visible;
			if (k.notNil and: { view.notNil } and: { view.isClosed.not }) {
				visible = this.prVisibleRows(view);
				if (k < offset or: { k >= (offset + visible) }) { offset = (k - (visible div: 2)).max(0) };
				this.prRedraw;
			}
		}.defer(0.1);
	}

	prRowAt { |y| ^rows[(y / this.class.rowH).floor.asInteger + offset] }

	// up and down move the selection; return unfolds or folds a group; held
	// still, left and right step through the last minute
	prKey {
		|key|
		var k = rows.detectIndex { |r| r[\key] == selKey }, by = 0, row;
		case
		{ key == 16r01000013 } { by = -1 }			// up
		{ key == 16r01000015 } { by = 1 }			// down
		{ key == 16r01000012 } { if (running.not) { this.stepFrame(-1) }; ^true }	// left
		{ key == 16r01000014 } { if (running.not) { this.stepFrame(1) }; ^true }	// right
		{ key == 16r01000004 } {						// return
			selected !? { byId[selected] !? { |n| if (n[\kind] == \group) {
				if (collapsed.includes(selected)) { collapsed.remove(selected) } { collapsed.add(selected) };
				this.prRebuild
			} } };
			^true
		}
		{ ^nil };
		if (rows.isEmpty) { ^true };
		k = (k ?? { if (by > 0) { offset - 1 } { offset + 1 } }) + by;
		// headers are not places to stop
		while { k >= 0 and: { k < rows.size } and: { #[\header, \verdict].includes(rows[k][\type]) } } { k = k + by };
		row = rows[k.clip(0, rows.size - 1)];
		row !? { this.prSelectRow(row); this.prScrollTo(row[\key]) };
		^true
	}

	// what a click on a row selects
	prSelectRow {
		|row|
		switch(row[\type],
			\node, { this.select(row[\node][\id]) },
			\record, { this.select(row[\record][\id]) },
			\bus, { var r = row[\range]; this.selectBus(r[\rate], r[\index], r[\channels]) },
			\problem, { this.prSelectThing(row[\problem][\id], row[\problem][\bus], row[\key]) },
			\log, { this.prSelectThing(row[\entry][\id], nil, row[\key]) },
			\diff, { this.prSelectThing(row[\change][\id], row[\change][\bus], row[\key]) },
			\buffer, { this.selectBuffer(row[\bufnum]) },
			\step, {
				var s = row[\step];
				case
				{ s[\step] == \bus } { this.prSelectThing(nil, [s[\rate], s[\index], s[\channels]], row[\key]) }
				{ s[\id].notNil } { this.prSelectThing(s[\id], nil, row[\key]) };
			},
			{ });
	}

	// select a node or a bus from a row that is about it; the row stays the lit one
	prSelectThing {
		|id, bus, key|
		case
		{ id.notNil and: { this.recordOf(id).notNil } } { this.select(id) }
		{ bus.notNil } { this.selectBus(bus[0], bus[1], bus[2]) }
		{ ^this };
		selKey = key;
		this.prRedraw;
	}

	prClick {
		|v, x, y, clicks|
		var row = this.prRowAt(y), node, tx;
		view.focus(true);
		if (row.isNil) { ^this };
		switch(row[\type],
			\node, {
				node = row[\node];
				tx = 8 + (row[\depth] * this.class.indent);
				if (node[\kind] == \group and: { x < (tx + 12) or: { clicks == 2 } }) {
					// the triangle folds; so does a double click on the row
					if (collapsed.includes(node[\id])) { collapsed.remove(node[\id]) } { collapsed.add(node[\id]) };
					this.prRebuild;
				} {
					this.select(node[\id]);
					if (clicks == 2 and: { node[\kind] == \synth } and: { server.serverRunning } and: { viewing.isNil }) {
						server.sendMsg('/n_trace', node[\id])
					};
				}
			},
			\events, {
				if (expanded.includes(row[\parent])) { expanded.remove(row[\parent]) } { expanded.add(row[\parent]) };
				this.prRebuild;
			},
			\bus, {
				this.prSelectRow(row);
				if (clicks == 2) { this.showPath(selBus) };
			},
			\step, {
				this.prSelectRow(row);
				// a double click follows the path from there
				if (clicks == 2) {
					if (row[\step][\step] == \bus) { this.showPath([row[\step][\rate], row[\step][\index], row[\step][\channels]]) } {
						row[\step][\id] !? { |id| this.showPath(id) }
					}
				};
			},
			\problem, { this.prSelectRow(row); if (clicks == 2) { row[\problem][\id] !? { |id| this.inspect(id) } } },
			\log, { this.prSelectRow(row); if (clicks == 2) { row[\entry][\id] !? { |id| if (byId[id].notNil) { this.inspect(id) } } } },
			\diff, { this.prSelectRow(row); if (clicks == 2) { row[\change][\id] !? { |id| if (byId[id].notNil) { this.inspect(id) } } } },
			{ this.prSelectRow(row) });
	}

	// the same def, the same colour: a hue from its name
	prDefColor { |def| ^this.class.defColor(def) }

	*defColor { |def| ^Color.hsv((def.asString.hash.abs % 1000) / 1000, 0.45, 0.95) }

	// what a synth's row says at the right: the describers' name, or the note.
	// Kept until the list is laid out again: the describers are not cheap.
	prNoteFor {
		|node|
		var id = node[\id], cached = noteCache[id];
		if (cached.isNil) {
			cached = this.class.describe(server, node)[\name] ? false;
			noteCache[id] = cached;
		};
		^if (cached == false) { nil } { cached }
	}

	// ------------------------------------------------------------------ drawing

	// a level as a bar: -60 dB at the left, 0 at the right; nil is not measured
	prLevelBar {
		|x, y, w, h, amp, bad = false|
		var frac;
		Pen.fillColor = SPWidgetBase.colBg;
		Pen.fillRect(Rect(x, y, w, h));
		if (amp.isNil) { ^this };
		frac = ((amp.max(1e-9).ampdb + 60) / 60).clip(0, 1);
		Pen.fillColor = case
		{ bad } { SPWidgetBase.colClip }
		{ amp >= 1 } { SPWidgetBase.colClip }
		{ amp >= 0.5 } { SPWidgetBase.colAccent }
		{ true } { SPWidgetBase.colGood };
		if (bad) { frac = 1 };
		Pen.fillRect(Rect(x, y, w * frac, h));
	}

	*dbText { |amp| ^if (amp.isNil) { "" } { if (amp < 0.00001) { "silent" } { amp.ampdb.round(1).asInteger.asString ++ " dB" } } }

	// A level as text that holds still long enough to be read: written again
	// twice a second at most, whatever the bar beside it does. key says whose.
	steadyDb {
		|key, amp|
		var now = Main.elapsedTime, e = dbShown[key];
		if (e.isNil or: { (now - e[1]) >= 0.5 }) {
			e = [this.class.dbText(amp), now];
			dbShown[key] = e;
		};
		^e[0]
	}

	// the light at the left of a row: how much comes out, red for a bad value
	prLight {
		|y, rh, amp, bad|
		if (bad) { Pen.fillColor = SPWidgetBase.colClip; Pen.fillRect(Rect(1, y + 2, 3, rh - 4)); ^this };
		if (amp.isNil) { ^this };
		if (amp < SPRouting.silence) {
			Pen.fillColor = SPWidgetBase.colGrid.copy.alpha_(0.5);
		} {
			Pen.fillColor = SPWidgetBase.colGood.copy.alpha_(((amp.ampdb + 60) / 60).clip(0.35, 1));
		};
		Pen.fillRect(Rect(1, y + 2, 3, rh - 4));
	}

	prDraw {
		|v|
		var w = v.bounds.width, h = v.bounds.height, rh = this.class.rowH;
		var now = Main.elapsedTime, visible = this.prVisibleRows(v), font = this.font(10), bold = this.font(10, true);
		offset = offset.clip(0, (rows.size - visible).max(0));

		if (tree.isNil and: { #[\log, \recent, \buffers].includes(mode).not }) {
			this.prText(if (server.serverRunning) { "asking the server..." } { "the server is not running" }, 10, 10);
			^this
		};
		if (rows.isEmpty) {
			this.prText(case
				{ filter.notEmpty } { "nothing matches \"%\"".format(filter) }
				{ mode == \recent } { "nothing has ended since the window opened" }
				{ mode == \log } { "nothing has happened since the window opened" }
				{ mode == \problems } { "nothing looks wrong" }
				{ mode == \busses } { "no bus is in use by a synth whose SynthDef is known here" }
				{ true } { "nothing to show" }, 10, 10)
		};

		rows.copyRange(offset, (offset + visible).min(rows.size - 1)).do { |row, k|
			var y = k * rh;
			if (row[\key] == selKey) {
				Pen.fillColor = SPWidgetBase.colAccent.copy.alpha_(0.22);
				Pen.fillRect(Rect(0, y, w, rh));
			};
			this.prSafely("drawing a row", { switch(row[\type],
				\node, { this.prDrawNode(row, y, w, rh, now, font, bold) },
				\record, { this.prDrawRecord(row, y, w, rh, now, font) },
				\events, { this.prDrawEvents(row, y, w, rh) },
				\header, { Pen.stringAtPoint(row[\text], Point(8, y + 3), this.font(9, true), SPWidgetBase.colDim) },
				\bus, { this.prDrawBus(row, y, w, rh, font) },
				\problem, { this.prDrawProblem(row, y, w, rh, font) },
				\log, { this.prDrawLog(row, y, w, rh, font) },
				\verdict, { this.prDrawVerdict(row, y, w, rh, bold) },
				\step, { this.prDrawStep(row, y, w, rh, font) },
				\buffer, { this.prDrawBuffer(row, y, w, rh, font) },
				\diff, { this.prDrawDiff(row, y, w, rh, font) }) });
		};

		// where we are, when there is more than fits
		if (rows.size > visible) {
			var barH = (h * visible / rows.size).max(16), barY = (h - barH) * offset / (rows.size - visible).max(1);
			Pen.fillColor = SPWidgetBase.colGrid.copy.alpha_(0.7);
			Pen.fillRect(Rect(w - 4, barY, 3, barH));
		};

		// Keep lighting the new ones until their second is up: one redraw
		// waiting at a time - each draw asking for another would multiply
		// (a hundred draws a second under a pattern, measured 2026-11-14).
		born.keys.copy.do { |id| if ((now - born[id]) > 1) { born.removeAt(id) } };
		if (born.notEmpty and: { running } and: { bornDue.isNil or: { now > (bornDue + 1) } }) {
			bornDue = now + 0.25;
			{ bornDue = nil; this.prRedraw }.defer(0.25);
		};
	}

	prGuides {
		|depth, y, rh|
		var ind = this.class.indent;
		Pen.strokeColor = SPWidgetBase.colGrid.copy.alpha_(0.35);
		Pen.width = 1;
		depth.do { |d| var gx = (8 + (d * ind) + 4).round + 0.5; Pen.line(Point(gx, y), Point(gx, y + rh)) };
		Pen.stroke;
	}

	prDrawNode {
		|row, y, w, rh, now, font, bold|
		var stale = row[\node], depth = row[\depth], x = 8 + (depth * this.class.indent);
		// what the server last said of it; a node that ended since the list was laid out stays, dimmed
		var node = byId[stale[\id]] ? stale, gone = byId[stale[\id]].isNil;
		var isNew = born[node[\id]].notNil, idStr = node[\id].asString;
		var marks = this.problemsOf(node[\id]).count { |p| p[\level] != \info }, right = w - 8;
		this.prGuides(depth, y, rh);
		if (node[\kind] == \group) {
			var open = collapsed.includes(node[\id]).not, n = row[\count] ?? { [this.prCount(node)[1], false] }, name = labels[node[\id]];
			Pen.fillColor = SPWidgetBase.colPanel.copy.alpha_(0.55);
			Pen.fillRect(Rect(x - 2, y + 1, w - x - 2, rh - 2));
			this.prTriangle(x + 4, y + (rh / 2), open);
			Pen.stringAtPoint(name ? "group", Point(x + 14, y + 2), bold,
				case { gone } { SPWidgetBase.colDim } { isNew } { SPWidgetBase.colAccent }
				{ name.notNil } { SPWidgetBase.colText } { SPWidgetBase.colDim });
			this.prText(idStr ++ if (gone) { "  ended" } { "" }, x + 18 + (name ? "group").bounds(bold).width, y + 4);
			this.prText(if (n[0] == 1) { "1 synth" } { "% synths".format(n[0]) } ++ if (n[1]) { " + short-lived" } { "" }, right, y + 4, nil, \right);
		} {
			var def = node[\def].asString, dx = x + 14, tx, note = this.prNoteFor(node), isPaused = this.pausedIds.includes(node[\id]);
			var tag = idStr ++ case { gone } { "  ended" } { isPaused } { "  paused" } { "" };
			this.prLight(y, rh, if (gone) { nil } { this.outLevel(node[\id]) }, gone.not and: { this.isBad(node[\id]) });
			Pen.fillColor = this.prDefColor(def).copy.alpha_(if (gone) { 0.4 } { 1 });
			Pen.fillOval(Rect(x + 1, y + (rh / 2) - 4, 8, 8));
			Pen.stringAtPoint(def, Point(dx, y + 2), font,
				case { gone } { SPWidgetBase.colDim } { isNew } { SPWidgetBase.colAccent } { isPaused } { SPWidgetBase.colDim } { SPWidgetBase.colText });
			tx = dx + def.bounds(font).width + 6;
			this.prText(tag, tx, y + 4);
			// something looks wrong with it: a mark at the right
			if (marks > 0) {
				Pen.fillColor = SPWidgetBase.colClip;
				Pen.fillOval(Rect(right - 8, y + (rh / 2) - 4, 8, 8));
				right = right - 14;
			};
			// what it is, at the right, where a group has its count
			note !? { this.prText(note, right, y + 4, nil, \right) };
			if (showControls) {
				node[\controls] !? { |cs|
					var str = cs.collect { |p| "% %".format(p[0], this.prValueString(p[1])) }.join("  ");
					var cx = tx + tag.bounds(this.font(9)).width + 10;
					var room = right - cx - 8 - (note !? { |n| n.bounds(this.font(9)).width } ? 0);
					if (room > 30) { this.prText(this.prFit(str, room), cx, y + 4) };
				}
			};
		};
	}

	// an ended synth: its def, where it was, how long ago
	prDrawRecord {
		|row, y, w, rh, now, font|
		var node = row[\record], x = 8, def = node[\def].asString, dx = x + 14, tx, right, room;
		Pen.fillColor = this.prDefColor(def).copy.alpha_(0.6);
		Pen.fillOval(Rect(x + 1, y + (rh / 2) - 4, 8, 8));
		Pen.stringAtPoint(def, Point(dx, y + 2), font, SPWidgetBase.colDim);
		tx = dx + def.bounds(font).width + 6;
		// the time it ended, not how long ago: a column of numbers that all tick is not read
		right = "ended %  ·  lived %".format(SPLog.clock(node[\ended]).drop(-2), SPInspector.duration(node[\ended] - node[\born]));
		this.prText(right, w - 8, y + 4, nil, \right);
		room = w - 18 - right.bounds(this.font(9)).width - tx;
		if (room > 30) { this.prText(this.prFit(node[\id].asString ++ "   " ++ (node[\pathText] ? ""), room), tx, y + 4) };
	}

	// a busy group's short-lived children, as one line
	prDrawEvents {
		|row, y, w, rh|
		var x = 8 + (row[\depth] * this.class.indent);
		this.prGuides(row[\depth], y, rh);
		this.prTriangle(x + 4, y + (rh / 2), row[\open]);
		this.prText(this.prFit(row[\text] ++ if (row[\open]) { "  ·  shown one by one below" } { "" }, w - x - 24), x + 14, y + 4, SPWidgetBase.colMeter);
	}

	prNames {
		|ids|
		var counts = Dictionary.new, order = List.new;
		ids.do { |id|
			var name = model[\nodes][id] !? { |n| n[\def].asString } ? id.asString;
			if (counts[name].isNil) { order.add(name) };
			counts[name] = (counts[name] ? 0) + 1;
		};
		^order.collect { |n| if (counts[n] > 1) { "% ×%".format(n, counts[n]) } { n } }.join(", ")
	}

	// a bus: its number and name, who writes it, who reads it, its level
	prDrawBus {
		|row, y, w, rh, font|
		var r = row[\range], x = 8, num, level, bad, barW = 70, right = w - 10, marks, mid, text;
		num = if (r[\channels] > 1) { "%-%".format(r[\index], r[\index] + r[\channels] - 1) } { r[\index].asString };
		Pen.stringAtPoint(num, Point(x, y + 2), font, SPWidgetBase.colText);
		this.prText(this.prFit(r[\name] ? "", 150), x + 54, y + 4, SPWidgetBase.colMeter);
		if (r[\rate] == \audio) {
			level = this.levelOver(r[\index], r[\channels]);
			bad = viewing.isNil and: { r[\channels].collect { |c| probe.bad(r[\index] + c) }.maxItem > 0 };
			this.prText(if (row[\idle] == true) { "" } { this.steadyDb(row[\key], level) }, right, y + 4, nil, \right);
			this.prLevelBar(right - 52 - barW, y + 6, barW, rh - 12, level, bad);
			right = right - 60 - barW;
		} {
			this.prText(probe.kval(r[\index]) !? { |v| SPInspector.valueString(v) } ? "", right, y + 4, SPWidgetBase.colText, \right);
			right = right - 60;
		};
		marks = this.problemsOfBus(r[\rate], r[\index], r[\channels]).count { |p| p[\level] != \info };
		if (marks > 0) {
			Pen.fillColor = SPWidgetBase.colClip;
			Pen.fillOval(Rect(right - 8, y + (rh / 2) - 4, 8, 8));
			right = right - 14;
		};
		mid = x + 210;
		text = if (row[\idle] == true) { "not in use right now" } {
			"%  →  %".format(if (r[\w].isEmpty) { "nothing writes it" } { this.prNames(r[\w]) },
				if (r[\r].isEmpty) { "nothing reads it" } { this.prNames(r[\r]) })
		};
		if ((right - mid) > 40) { this.prText(this.prFit(text, right - mid), mid, y + 4, if (row[\idle] == true) { SPWidgetBase.colDim } { SPWidgetBase.colText }) };
	}

	prDrawProblem {
		|row, y, w, rh, font|
		var p = row[\problem], warn = p[\level] != \info;
		Pen.fillColor = if (warn) { SPWidgetBase.colClip } { SPWidgetBase.colGrid };
		Pen.fillOval(Rect(9, y + (rh / 2) - 4, 8, 8));
		Pen.stringAtPoint(this.prFitFont(p[\text], w - 34, font), Point(24, y + 2), font, if (warn) { SPWidgetBase.colText } { SPWidgetBase.colDim });
	}

	prDrawLog {
		|row, y, w, rh, font|
		var e = row[\entry], color;
		color = switch(e[\kind],
			\fail, { SPWidgetBase.colClip },
			\problem, { SPWidgetBase.colAccent },
			\cleared, { SPWidgetBase.colGood },
			\snapshot, { SPWidgetBase.colMeter },
			\born, { SPWidgetBase.colText },
			{ SPWidgetBase.colDim });
		Pen.stringAtPoint(SPLog.clock(e[\time]), Point(8, y + 3), Font(Font.defaultMonoFace, 9), SPWidgetBase.colDim);
		e[\def] !? { |d| Pen.fillColor = this.prDefColor(d).copy.alpha_(if (e[\kind] == \born) { 1 } { 0.5 }); Pen.fillOval(Rect(80, y + (rh / 2) - 3, 6, 6)) };
		Pen.stringAtPoint(this.prFitFont(SPLog.lineOf(e), w - 106, font), Point(92, y + 2), font, color);
	}

	prDrawVerdict {
		|row, y, w, rh, bold|
		var side = row[\side] == true, font = if (side) { this.font(10) } { bold };
		Pen.fillColor = case { row[\ok] } { SPWidgetBase.colGood } { side } { SPWidgetBase.colGrid } { SPWidgetBase.colClip };
		Pen.fillOval(Rect(9, y + (rh / 2) - 4, 8, 8));
		Pen.stringAtPoint(this.prFitFont(row[\text], w - 34, font), Point(24, y + 2), font, if (side) { SPWidgetBase.colDim } { SPWidgetBase.colText });
	}

	// a step of a path: a synth, a bus with its level, or a remark
	prDrawStep {
		|row, y, w, rh, font|
		var s = row[\step], x = 8 + (s[\depth] * this.class.indent), right = w - 10, barW = 70;
		this.prGuides(s[\depth], y, rh);
		switch(s[\step],
			\synth, {
				var node = model[\nodes][s[\id]], def = (node !? { node[\def] } ? "?").asString, note = node !? { this.prNoteFor(node) };
				Pen.fillColor = this.prDefColor(def);
				Pen.fillOval(Rect(x + 1, y + (rh / 2) - 4, 8, 8));
				Pen.stringAtPoint(def, Point(x + 14, y + 2), font, if (s[\paused]) { SPWidgetBase.colClip } { SPWidgetBase.colText });
				this.prText("%%%".format(s[\id], if (s[\paused]) { "  paused" } { "" }, if (s[\again] == true) { "  ·  again: what follows it is above" } { "" }),
					x + 20 + def.bounds(font).width, y + 4, if (s[\paused]) { SPWidgetBase.colClip } { nil });
				note !? { this.prText(note, right, y + 4, nil, \right) };
			},
			\bus, {
				var level = if (s[\rate] == \audio) { this.levelOver(s[\index], s[\channels]) };
				var text = (x = x + 8; "% %").format(if (s[\feed] == true) { "reads" } { "→" }, SPRouting.busText(server, s[\rate], s[\index], s[\channels]))
					++ if (s[\end] == true) { "  ·  the way out" } { "" }
					++ if (s[\feed] == true) {
						"  ←  " ++ if (s[\from].isEmpty) { "nothing writes it before" } { this.prNames(s[\from]) }
					} { "" };
				if (s[\rate] == \audio) {
					this.prText(this.steadyDb(row[\key], level), right, y + 4, nil, \right);
					this.prLevelBar(right - 52 - barW, y + 6, barW, rh - 12, level);
					right = right - 60 - barW;
				};
				this.prText(this.prFit(text, right - x - 14), x + 14, y + 4, if (s[\end] == true) { SPWidgetBase.colGood } { SPWidgetBase.colMeter });
			},
			{
				this.prText(this.prFit(s[\text] ? "", right - x - 14), x + 14, y + 4, if (s[\warn] == true) { SPWidgetBase.colClip } { SPWidgetBase.colDim });
			});
	}

	prDrawBuffer {
		|row, y, w, rh, font|
		var used = row[\users].notEmpty, right = if (used) { if (row[\users].size == 1) { "1 synth reads it" } { "% synths read it".format(row[\users].size) } } { "" };
		Pen.stringAtPoint(row[\bufnum].asString, Point(8, y + 2), font, if (used) { SPWidgetBase.colText } { SPWidgetBase.colDim });
		this.prText(right, w - 10, y + 4, SPWidgetBase.colGood, \right);
		this.prText(this.prFit(row[\text], w - 80 - right.bounds(this.font(9)).width), 60, y + 4, if (used) { SPWidgetBase.colText } { SPWidgetBase.colDim });
	}

	prDrawDiff {
		|row, y, w, rh, font|
		var c = row[\change];
		Pen.fillColor = switch(c[\kind], \added, { SPWidgetBase.colGood }, \removed, { SPWidgetBase.colClip },
			\moved, { SPWidgetBase.colAccent }, \problem, { SPWidgetBase.colClip }, { SPWidgetBase.colMeter });
		Pen.fillOval(Rect(9, y + (rh / 2) - 4, 8, 8));
		Pen.stringAtPoint(this.prFitFont(c[\text], w - 34, font), Point(24, y + 2), font, SPWidgetBase.colText);
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
	prFit { |str, width| ^this.prFitFont(str, width, this.font(9)) }

	prFitFont {
		|str, width, font|
		var n, full;
		str = str ? "";
		full = str.bounds(font).width;
		if (full <= width) { ^str };
		n = (str.size * width / full.max(1)).floor.asInteger - 1;
		str = str.keep(n.max(0));
		// never half a character: a String is bytes, and › is three of them
		while { str.notEmpty and: { str.last.ascii < 0 } } { str = str.drop(-1) };
		^str ++ "…"
	}

	prHover {
		|v, x, y|
		var row = this.prRowAt(y), node;
		if (row.isNil) { ^"" };
		^switch(row[\type],
			\record, {
				"synth %  %  ·  ended (when the tree last lacked it) · click to see what it was".format(row[\record][\id], row[\record][\def])
			},
			\node, {
				node = row[\node];
				if (node[\kind] == \group) {
					"group %%  ·  click to inspect, the triangle or a double click to %".format(node[\id],
						labels[node[\id]] !? { |l| " (" ++ l ++ ")" } ? "",
						if (collapsed.includes(node[\id])) { "open" } { "fold" })
				} {
					"synth %  %%%  ·  click to inspect, double click to trace".format(node[\id], node[\def],
						this.prNoteFor(node) !? { |n| " (" ++ n ++ ")" } ? "",
						this.outLevel(node[\id]) !? { |l| "  ·  the busses it writes: " ++ this.class.dbText(l) } ? "")
				}
			},
			\events, { "this group makes many nodes that live a moment (a pattern's events): they are one line, so the list holds still. Click to " ++ if (row[\open]) { "have them as one line again" } { "see them one by one" } ++ " - the log and ended have each of them" },
			\bus, {
				var r = row[\range];
				"% · written by: %  ·  read by: %  ·  click to inspect, double click for where it goes".format(
					SPRouting.busText(server, r[\rate], r[\index], r[\channels], r[\name]),
					if (r[\w].isEmpty) { "nothing" } { r[\w].asSet.asArray.sort.collect { |id| SPRouting.nodeText(model, id) }.join(", ") },
					if (r[\r].isEmpty) { "nothing" } { r[\r].asSet.asArray.sort.collect { |id| SPRouting.nodeText(model, id) }.join(", ") })
			},
			\problem, { row[\problem][\text] },
			\log, { SPLog.clock(row[\entry][\time]) ++ "  " ++ SPLog.lineOf(row[\entry]) },
			\verdict, { row[\text] },
			\step, {
				var s = row[\step];
				case
				{ s[\step] == \synth } { "% · click to inspect, double click to follow its path from here".format(SPRouting.nodeText(model, s[\id])) }
				{ s[\step] == \bus } { "% · click to inspect, double click to follow the path from this bus".format(SPRouting.busText(server, s[\rate], s[\index], s[\channels])) }
				{ true } { s[\text] ? "" }
			},
			\buffer, { "buffer %  %  ·  click to see which synths read it".format(row[\bufnum], row[\text]) },
			\diff, { row[\change][\text] },
			{ row[\text] ? "" })
	}

	// ---------------------------------------------------------------- the strip

	// The last minute and a half, newest at the right: what started (up) and
	// ended (down) in each half second, a mark where a problem or a FAILURE
	// came, a line where a snapshot was taken. Held still, a click steps back
	// to that moment.
	prStripNow { ^if (running) { Main.elapsedTime } { freezeTime ? Main.elapsedTime } }

	// what the strip draws, kept as it happens: a half second a bin, [started, ended, mark, snapshot]
	prStripNote {
		|e|
		var b = (e[\time] / 0.5).floor.asInteger, bin = stripBins[b];
		if (bin.isNil) {
			bin = [0, 0, 0, false];
			stripBins[b] = bin;
			if (stripBins.size > 600) { stripBins.keys.copy.do { |k| if (k < (b - 400)) { stripBins.removeAt(k) } } };
		};
		switch(e[\kind],
			\born, { bin[0] = bin[0] + 1 },
			\ended, { bin[1] = bin[1] + 1 },
			\fail, { bin[2] = 2 },
			\problem, { bin[2] = bin[2].max(1) },
			\snapshot, { bin[3] = true });
	}

	prDrawStrip {
		|v|
		var w = v.bounds.width, h = v.bounds.height, now = this.prStripNow, span = rewind;
		// the first 86 points are for the words: the bars never run under them
		var gutter = 86, nbins = (span / 0.5).asInteger, last = (now / 0.5).floor.asInteger, bw = (w - gutter) / nbins;
		var mid = (h / 2).round + 0.5, top = 4, bins;
		bins = nbins.collect { |k| stripBins[last - nbins + 1 + k] };
		bins.do { |bin| if (bin.notNil) { top = top.max(bin[0]).max(bin[1]) } };
		Pen.strokeColor = SPWidgetBase.colGrid.copy.alpha_(0.5);
		Pen.width = 1;
		Pen.line(Point(gutter, mid), Point(w, mid));
		Pen.stroke;
		bins.do { |bin, k|
			if (bin.notNil) {
				var x = gutter + (k * bw);
				if (bin[0] > 0) {
					Pen.fillColor = SPWidgetBase.colMeter;
					Pen.fillRect(Rect(x, mid - ((bin[0] / top) * (mid - 3)), (bw - 1).max(1), (bin[0] / top) * (mid - 3)));
				};
				if (bin[1] > 0) {
					Pen.fillColor = SPWidgetBase.colGrid;
					Pen.fillRect(Rect(x, mid, (bw - 1).max(1), (bin[1] / top) * (mid - 3)));
				};
				if (bin[2] > 0) {
					Pen.fillColor = if (bin[2] == 2) { SPWidgetBase.colClip } { SPWidgetBase.colAccent };
					Pen.fillRect(Rect(x, 0, (bw - 1).max(2), 3));
				};
				if (bin[3]) {
					Pen.strokeColor = SPWidgetBase.colText;
					Pen.width = 1;
					Pen.line(Point(x.round + 0.5, 0), Point(x.round + 0.5, h));
					Pen.stroke;
				};
			}
		};
		// the moment looked at
		if (viewing.notNil and: { viewTitle.isNil }) {
			var x = (w - ((now - viewing[\time]) / span * (w - gutter))).round.max(gutter) + 0.5;
			Pen.strokeColor = SPWidgetBase.colAccent;
			Pen.width = 2;
			Pen.line(Point(x, 0), Point(x, h));
			Pen.stroke;
		};
		this.prText(case
			{ running } { "now" }
			{ viewTitle.notNil } { "a snapshot" }
			{ viewing.notNil } { SPInspector.duration(now - viewing[\time]) ++ " ago" }
			{ true } { "held still" }, 6, 2, if (running) { SPWidgetBase.colDim } { SPWidgetBase.colAccent });
		this.prText("last % s".format(span), 6, h - 13);
	}

	prStripTime { |v, x| ^this.prStripNow - ((1 - ((x - 86) / (v.bounds.width - 86).max(1))).clip(0, 1) * rewind) }

	prStripHover {
		|v, x|
		^"% ago: nodes started (up) and ended (down) each half second; orange a problem, red a FAILURE. Click to hold still at that moment, then left and right step; run goes back to now"
			.format(SPInspector.duration(this.prStripNow - this.prStripTime(v, x)))
	}

	prStripClick {
		|v, x, clicks|
		var frame;
		if (running) { this.running = false };
		frame = this.prFrameAt(this.prStripTime(v, x));
		if (frame.notNil and: { frame !== viewing }) { this.prView(frame, nil) };
	}

	// --------------------------------------------------------------- watching

	// Keep a value in view under the list: a control of a synth, or a bus.
	// what is (kind: \control, id:, index:, name:, def:) or (kind: \bus, rate:, index:, name:).
	watchToggle {
		|what|
		watch.toggle(what);
		this.prProbeSet;
		{
			if (this.isOpen) {
				watchView.visible = watch.isEmpty.not;
				watchView.fixedHeight = (watch.size.min(8) * this.class.rowH) + 4;
				watchView.refresh;
			}
		}.defer;
		this.prShowSelected;
	}

	isWatched { |what| ^watch.includes(what) }

	prDrawWatch {
		|v|
		var w = v.bounds.width, rh = this.class.rowH, font = this.font(9), lineW = 160;
		watch.items.keep(8).do { |item, k|
			var y = 2 + (k * rh), hist = item[\hist], value = item[\value], gone = item[\ended] == true, lo, hi, text;
			var audio = item[\kind] == \bus and: { item[\rate] == \audio };
			this.prText("×", 8, y + 3, SPWidgetBase.colDim);
			this.prText(this.prFit(SPWatch.labelOf(item) ++ if (gone) { "  ·  ended" } { "" }, w - lineW - 130), 24, y + 4,
				if (gone) { SPWidgetBase.colDim } { SPWidgetBase.colText });
			text = case { value.isNil } { "" } { audio } { this.steadyDb(item[\key], value) } { true } { SPInspector.valueString(value) };
			this.prText(text, w - lineW - 16, y + 4, SPWidgetBase.colText, \right);
			// the last moments as a line, between the least and the most of them
			if (hist.size > 1) {
				var vals = if (audio) { hist.collect { |x| x.max(0.001).ampdb } } { hist.asArray };
				lo = vals.minItem; hi = vals.maxItem;
				if (hi <= lo) { hi = lo + 1; lo = lo - 1 };
				Pen.strokeColor = SPWidgetBase.colMeter;
				Pen.width = 1;
				vals.do { |x, i|
					var px = w - lineW - 8 + (i / (SPWatch.historySize - 1) * lineW), py = y + rh - 3 - ((x - lo) / (hi - lo) * (rh - 6));
					if (i == 0) { Pen.moveTo(Point(px, py)) } { Pen.lineTo(Point(px, py)) };
				};
				Pen.stroke;
			};
		};
	}

	prWatchClick {
		|x, y|
		var item = watch.items[((y - 2) / this.class.rowH).floor.asInteger];
		if (item.isNil) { ^this };
		if (x < 20) { ^this.watchToggle(item) };
		if (item[\kind] == \control) { if (this.recordOf(item[\id]).notNil) { this.select(item[\id]) } } {
			this.selectBus(item[\rate], item[\index], 1)
		};
	}

	// ---------------------------------------------------------------- listening

	// Hear an audio bus on the outputs (SPProbe.listenOut), beside what plays;
	// again on the same bus stops it.
	listen {
		|bus, channels = 1|
		if (probe.isListeningTo(bus)) { probe.stopListening } { probe.listen(bus, channels) };
		this.prShowSelected;
	}

	isListeningTo { |bus| ^probe.isListeningTo(bus) }
}
