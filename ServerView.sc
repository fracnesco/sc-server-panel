ServerView : Singleton {
	classvar panelTypes, <>widgets, <actions, <>border=true,
	showOnBoot=true, serverViewShown=false;
	var <>view, <window, widgetLayout, server, <>widgets, containers;

	*initClass {
		panelTypes = IdentityDictionary();
		// the master fx row sits under the counters, and only exists
		// when the ParaEQ extension - FxChain - is compiled in
		widgets = List[ServerStatusWidget]
			++ (if (\FxChain.asClass.notNil) { [MasterFXWidget] } { [] })
			++ [ScopeWidget, HistoryWidget, VolumeWidget, RecordWidget];
		actions = IdentityDictionary();

		ServerBoot.add(this);
		Platform.makeServerWindowAction = {
			this.doOnServerBoot();
		}
	}

	*doOnServerBoot {
		|server|
		if (showOnBoot && serverViewShown.not) {
			serverViewShown = true;
			ServerView().front;
		};

		if (Server.all.select(_.serverRunning).size == 0) {
			// If no other servers running, set this one to display
			ServerView().server = server;
		}
	}

	*default {
		^Server.default.name;
	}

	server {
		var runningServers = Server.all.select(_.serverRunning).asArray;
		^server
			?? { if (runningServers.size == 1) { runningServers[0] } }
			?? { Server.default }
			?? { Server.local }
	}

	set {}

	close {
		{ window.close() }.defer;
	}

	front {
		if (server.isNil) {
			//server = Server.default;
		};
		if (view.isNil) {
			this.createView();
		};
		view.front;
	}

	createView {
		window = Window("server", Rect(0, 0, 265, 200), resizable:true, border:border);
		window.autoRememberPosition(\ServerView);
		view = window.view.minWidth_(265).minHeight_(200);

		// the dark palette every module window is drawn in, so the Qt
		// widgets that are not coloured by hand - sliders, menus, number
		// boxes - come out the same as theirs
		if (GUI.id == \qt) { view.palette = QPalette.dark };
		view.background = ServerWidgetBase.colBg;

		view.keyDownAction_({
			|v, char|
			actions[char.asSymbol].value(this);
		});
		if (border.not) {
			view.layout_(HLayout(
				WindowHandleView().maxWidth_(10).minWidth_(10),
				widgetLayout = VLayout()
			).spacing_(0).margins_(6));
		}{
			view.layout_(HLayout(
				widgetLayout = VLayout()
			).spacing_(0).margins_(6));
		};

		widgetLayout.margins = 2;
		widgetLayout.spacing = 1;
		view.onClose_(this.onClose(_));
		this.populateView();
	}

	populateView {
		containers = List();
		widgets = List();

		([ServerSelectorWidget] ++ this.class.widgets).do {
			|wClass, i|
			var container, widget;
			widget = wClass.new(this.server, this).view();
			widgets.add(widget);
			container = View().layout_(
				VLayout(
					widget,
					if (i != this.class.widgets.size()) {
						UserView().fixedHeight_(10).drawFunc_({
							|v|
							var y = (v.bounds.height / 2).round + 0.5;
							Pen.strokeColor = ServerWidgetBase.colGrid.copy.alpha_(0.5);
							Pen.width = 1;
							Pen.line(2@y, (v.bounds.width - 2)@y);
							Pen.stroke();
						})
					}
				).spacing_(1).margins_(0)
			).canFocus_(true);
			containers.add(container);
		};

		containers.do({
			|w|
			widgetLayout.add(w)
		});

		widgetLayout.add(nil);
	}

	onClose {
		|v|
		window = widgetLayout = view = nil;
		actions.clear;
	}

	clearView {
		if (view.notNil) {
			containers.do(_.remove);
		}
	}

	registerKeyAction {
		| key, action |
		// if (actions[key].notNil) {
		// 	"ServerView - Overriding an existing action for key %".format(key).warn;
		// };
		actions[key] = action;
	}

	server_{
		|newServer|
		if (server != newServer) {
			{
				server = newServer;
				if (view.notNil) {
					this.clearView();
					this.populateView();
				}
			}.defer();
		}
	}
}

// =====================================================================
// ServerWidgetBase - what every ServerView widget is built from
//
// The colours and fonts are the ones the module family's windows are
// drawn in - BusRecorderGUI, InsightGUI, ModulePalette keep the same
// values - copied here so this quark depends on none of them. A widget
// reads them by name (colText, colPanel, ...) and makes its labels and
// buttons with prLabel and prButton, the way those windows do.
//
// Insight, BusRecorder and the master fx chain (FxChain, from the
// ParaEQ extension) are optional. Each is looked up by name at runtime
// and never referenced directly, so ServerView compiles and runs
// without them; the buttons and rows that open them only exist when
// the class does.
// =====================================================================

ServerWidgetBase {
	classvar <colBg, <colPanel, <colText, <colDim, <colAccent;
	classvar <colMeter, <colClip, <colOff, <colGrid, <colPeak, <colGood, <colRec;

	var server, parent;

	*initClass {
		colBg     = Color(0.13, 0.13, 0.15);
		colPanel  = Color(0.20, 0.20, 0.24);
		colText   = Color(0.86, 0.87, 0.89);
		colDim    = Color(0.55, 0.56, 0.62);
		colAccent = Color(0.98, 0.65, 0.20);
		colMeter  = Color(0.42, 0.76, 0.95);
		colClip   = Color(0.95, 0.35, 0.30);
		colOff    = Color(0.09, 0.10, 0.12);
		colGrid   = Color(0.30, 0.33, 0.40);
		colPeak   = Color(0.95, 0.96, 0.98);
		colGood   = Color(0.40, 0.86, 0.60);
		colRec    = Color(0.90, 0.25, 0.25);
	}

	*new {
		|server, parent|
		^super.newCopyArgs(server, parent).init();
	}

	// ------------------------------------------------ the sibling extensions

	*hasInsight { ^\Insight.asClass.notNil }

	*hasRecorder { ^\BusRecorder.asClass.notNil }

	// an insight on the main out of this server, window and all
	*openInsight {
		|server|
		var cls = \Insight.asClass;
		if (cls.isNil) { ^"ServerView: Insight is not installed".postln };
		^cls.open(server)
	}

	// the recorder window: one per session, brought to front if it is up
	*openRecorder {
		var cls = \BusRecorder.asClass;
		if (cls.isNil) { ^"ServerView: BusRecorder is not installed".postln };
		^cls.gui
	}

	*hasMasterFx { ^\FxChain.asClass.notNil }

	// the chain of effects on this server's main out - an eq and, when
	// that extension is installed too, a compressor
	*masterFx {
		|server|
		var cls = \FxChain.asClass;
		if (cls.isNil) { ^"ServerView: ParaEQ (FxChain) is not installed".postln };
		^cls.master(server)
	}

	// the same in a window of its own
	*openMasterFx {
		|server|
		var chain = this.masterFx(server);
		^chain.tryPerform(\gui)
	}

	init {
		this.actions.do({
			|action|
			parent.registerKeyAction(action.key, action.action);
		});
	}

	font {
		| size = 11, bold = false |
		^Font(Font.defaultSansFace, size, bold);
	}

	smallFont { ^this.font(9) }

	boldFont { ^this.font(12, true) }

	monoFont { ^Font(Font.defaultMonoFace, 11) }

	// the n-th channel of a scope: the meter blue, a hue step further round
	// for each channel so the traces can be told apart
	channelColor {
		|i|
		var h, s, v, a;
		#h, s, v, a = colMeter.asHSV;
		^Color.hsv((h + (i * 0.68)).mod(1).clip(0, 1), s, v, a)
	}

	prLabel {
		|str, width|
		^StaticText().string_(str).font_(this.smallFont).stringColor_(colDim)
			.fixedWidth_(width)
	}

	prButton {
		|str, width, func|
		^Button().fixedWidth_(width).states_([[str, colText, colPanel]])
			.canFocus_(false)
			.action_({ func.value })
	}

	actions { ^[] }

	view { this.subclassResponsibility('view') }
}

ServerViewAction {
	var <name, <key, <action;
	*new { |name, key, action| ^super.newCopyArgs(name, key, action) }
	value { |...args| action.value(*args) }
	asString { ^name }
}

ServerSelectorWidget : ServerWidgetBase {
	var serverList, view, runningText, bootButton, defaultButton, optionsMenu, optionsView, controller, serverQuitView;
	var connections;

	actions {
		var actions = [
			ServerViewAction("Options", 'o', {
				if (optionsView.isNil) {
					optionsView = ServerOptionsGui(server);
					optionsView.parent.autoRememberPosition(\ServerOptionsGui);
					optionsView.parent.onClose_({ optionsView = nil })
				};
			}),

			ServerViewAction("Query nodes", 'n',
				{ server.queryAllNodes(true) }),

			ServerViewAction("Level meters", 'l',
				{ server.tryPerform(\meter) }),

			ServerViewAction("Tree view", 'p',
				{ if(server.serverRunning) { TreeSnapshotView(server).autoUpdate().front() } }),

			ServerViewAction("Scope", 's',
				{
					var scopeWin = server.scope(server.options.numOutputBusChannels);
					scopeWin.scopeView.waveColors = 10.collect { |i| this.channelColor(i) };
				}
			),

			ServerViewAction("Frequency scope", 'f',
				{ server.freqscope }),

			ServerViewAction("Insight", 'i',
				{ ServerWidgetBase.openInsight(server) }),

			ServerViewAction("Recorder", 'r',
				{ ServerWidgetBase.openRecorder }),

			ServerViewAction("Dump OSC", 'd',
				{ if (server.isLocal or: { server.inProcess }) { server.dumpOSC((server.dumpMode + 1) % 2) } }),

			ServerViewAction("Mute", 'm',
				{ if (server.volume.isMuted) { server.unmute } { server.mute } }),

			ServerViewAction("Reset volume", '0',
				{ server.volume = 0 }),
		];

		actions = [

			ServerViewAction("Boot", 'b',
				{ if(server.serverRunning.not) { server.boot } }),

			ServerViewAction("Reboot", 'none',
				{ if(server.serverRunning.not) { server.reboot } }),

			ServerViewAction("Quit", 'none',
				{ if(server.serverRunning) { server.quit } }),
		] ++ actions;

		actions = actions ++ [
			ServerViewAction("Kill all servers", 'none',
				{ Server.killAll }),
			ServerViewAction("Close Server View", 27.asAscii.asSymbol,
				{ parent.window.rememberPosition(\ServerView); parent.close() }),

		];

		^actions
	}

	menuActions {
		^[
			MenuAction("Query nodes", {
				server.queryAllNodes(true)
			}).shortcut_("n"),

			MenuAction("Level meters", {
				server.tryPerform(\meter)
			}).shortcut_("l"),

			MenuAction("Tree view", {
				if(server.serverRunning) { TreeSnapshotView(server).autoUpdate().front() }
			}).shortcut_("p"),

			MenuAction("Scope", {
				var scopeWin = server.scope(server.options.numOutputBusChannels);
				scopeWin.scopeView.waveColors = 10.collect { |i| this.channelColor(i) };
			}).shortcut_("s"),

			MenuAction("Frequency scope", {
				server.freqscope
			}).shortcut_("f"),

			MenuAction("Insight", {
				ServerWidgetBase.openInsight(server)
			}).shortcut_("i"),

			MenuAction("Recorder", {
				ServerWidgetBase.openRecorder
			}).shortcut_("r"),

			MenuAction("Master fx", {
				ServerWidgetBase.openMasterFx(server)
			}).shortcut_("x"),

			MenuAction("Dump OSC", {
				|m|
				if (server.isLocal or: { server.inProcess }) {
					server.dumpOSC((server.dumpMode + 1) % 2);
					m.checked = (server.dumpMode > 0);
				}
			}).shortcut_("d"),

			MenuAction("Mute", {
				|m|
				if (server.volume.isMuted) { server.unmute } { server.mute };
				m.checked = server.volume.isMuted;
			}).checked_(server.volume.isMuted),

			MenuAction("Reset volume", {
				server.volume = 0
			}).shortcut_("0"),

			MenuAction("Options...", {
				if (optionsView.isNil) {
					optionsView = ServerOptionsGui(server);
					optionsView.parent.autoRememberPosition(\ServerOptionsGui);
					optionsView.parent.onClose_({ optionsView = nil })
				};
			}).shortcut_("o"),

			MenuAction.separator,

			MenuAction("Kill all servers", { Server.killAll }),
			MenuAction("Close Window", {
				parent.window.rememberPosition(\ServerView); parent.close()
			})
		]
	}

	// The server's name is the window's title, the way "recorder" and
	// "insight" head theirs: bold, accent while it runs, dim while it
	// does not. It is also the menu of servers.
	makeServerMenu {
		var menu, button, onServersChanged;
		var serverRunningConnection;

		button = Button()
					.canFocus_(false)
					.fixedHeight_(24)
					.minWidth_(120)
					.toolTip_("the server this window shows - click for the others");

		onServersChanged = {
			|what, obj, server|
			var displayedServer, running;

			displayedServer = parent.server;

			serverRunningConnection.free;
			serverRunningConnection = displayedServer.signal(\serverRunning).connectTo(onServersChanged).defer;

			running = displayedServer.serverRunning;

			button.states = [[
				"% %  %".format(
					running.if("◎", "◉"),
					displayedServer.name,
					running.if("running", "stopped")
				),
				running.if(colAccent, colDim),
				Color.clear
			]];

			button.font = this.boldFont;
		};

		connections = connections.addAll([
			Server.signal(\serverAdded).connectTo(onServersChanged).defer,
			parent.server.signal(\default).connectTo(onServersChanged).defer // all servers get notified, so no need to register everywhere
		]);

		onServersChanged.();
		MainMenu.tryPerform(\initBuiltInMenus);
		button.menu = MainMenu.serversMenu;

		{
			connections.add(
				MainMenu.serversMenu.signal(\triggered)
				.connectTo({
					|obj, what, action|
					Server.all.detect({ |s| action.string.contains(s.name.asString) }) !? {
						|s|
						parent.server = s;
						onServersChanged.();
					}
				})
			);
		}.defer;

		^button;
	}

	makeOptionsMenu {
		var button;
		button = Button()
					.fixedSize_(32@20)
					.states_([[nil, Color.clear, colPanel]])
					.canFocus_(false)
					.iconSize_(16)
					.toolTip_("everything the window can do, with its keys");

		if (\Material.asClass.notNil) {
			button.icon_(Material("settings", 18, color: colDim))
		} {
			button.states_([["☰", colDim, colPanel]])
		};

		button.menu = Menu(*this.menuActions);
		^button;
	}

	makeServerMenuOld {
		var serverList, servers;

		servers = Server.all.asArray;
		serverList = PopUpMenu();
		serverList.items = Server.all.collect({
			| s |
			s.name;
		});
		serverList.value = servers.indexOf(server);
		serverList.action = {
			|v|
			parent.server = servers[v.value];
		};
		serverList.maxHeight_(18);
		serverList.font = this.font(11);

		^serverList
	}

	makeOptionsMenuOld {
		var optionsMenu = PopUpMenu().maxHeight_(18).maxWidth_(22);

		optionsMenu.allowsReselection = true;
		optionsMenu.items = [""] ++ this.actions.collectAs(_.name, Array);
		optionsMenu.action_({
			|v|
			var name = v.items[v.value];
			this.actions.detect({ |action| action.name == name }).value(server)
		});

		^optionsMenu
	}

	view {
		var menus, menuSupport = \Menu.asClass.notNil;

		controller = (SimpleController(server)
			// .put(\serverRunning, this.onRunning(_,_))
			.put(\default, this.onDefault(_,_))
		);

		serverList 		= menuSupport.if({ this.makeServerMenu }, { this.makeServerMenuOld });
		optionsMenu		= menuSupport.if({ this.makeOptionsMenu }, { this.makeOptionsMenuOld });

		view = View();
		view.layout_(
			HLayout(
				[serverList, \align: \left],
				defaultButton = (Button()
					.action_(this.defaultAction(_))
					.fixedSize_(22@20)
					.canFocus_(false)
					.toolTip_("make this the default server")
				),
				nil,
				optionsMenu
			).margins_(0).spacing_(4)
		);

		this.onDefault();

		view.onClose_({ controller.remove() });

		^view;
	}

	bootAction {
		if (server.serverRunning.not) {
			server.boot;
		}
	}

	defaultAction {
		Server.default = server;
	}

	killAction {
		Server.killAll;
	}

	onDefault {
		if (Server.default == server) {
			defaultButton
				.states_([["D", Color.black, colAccent]])
				.font_(this.font(10, true))
		} {
			defaultButton
				.states_([["D", colDim, colPanel]])
				.font_(this.font(10))
		}
	}

}

ServerStatusWidget : ServerWidgetBase {
	classvar <allCounters;
	var view, controller, columns=3, trackedCounts, counters;

	*initClass {
		allCounters = List[\numSynths, \numGroups, \numUGens, \avgCPU, \numSynthDefs, \peakCPU, \sampleRate, \actualSampleRate];
	}

	view {
		var counterViews;
		controller = (SimpleController(server)
			.put(\counts, this.onCounts(_,_))
		);

		counters = IdentityDictionary();
		trackedCounts = [\numSynths, \numGroups, \avgCPU, \numUGens, \numSynthDefs, \peakCPU];
		trackedCounts.do {
			|countName|
			counters[countName] = this.perform( ("create_" ++ countName).asSymbol);
		};
		counterViews = List();
		trackedCounts.do({|c|
			counters[c].view.background_(colOff);
			counterViews.add([ counters[c].view, columns:counters[c].span ]);
			(counters[c].span - 1).do { counterViews.add(nil) }
		});
		counterViews = counterViews.clump(columns);

		view = View().layout_(
			GridLayout.rows(*counterViews).spacing_(1).margins_(0)
		);

		view.onClose_({ controller.remove() });

		^view;
	}

	onCounts {
		allCounters.do {
			|counterName|
			counters[counterName] !? { counters[counterName].value = server.perform(counterName) };
		}
	}

	create_numUGens {
		^GraphCounter("ugens", "", this.smallFont, colMeter.copy.alpha_(0.6), 0);
	}

	create_numSynths {
		^GraphCounter("synths", "", this.smallFont, colMeter.copy.alpha_(0.6), 0);
	}

	create_numGroups {
		^NumberCounter("groups", "", this.smallFont);
	}

	create_numSynthDefs {
		^NumberCounter("synthdefs", "", this.smallFont);
	}

	create_avgCPU {
		^GraphCounter("avg cpu", "%", this.smallFont, colGood, 0, 100, colClip.copy.alpha_(0.6));
	}

	create_peakCPU {
		^GraphCounter("peak cpu", "%", this.smallFont, colGood, 0, 100, colClip.copy.alpha_(0.6));
	}
}

NumberCounter {
	var name, units, font, <view, heading, number, <>span=1;

	*new {
		|name, units, font|
		^super.newCopyArgs(name, units, font).init;
	}

	init {
		view = View().layout_(VLayout(
			heading = (StaticText()
				.string_(name)
				.font_(font)
				.stringColor_(ServerWidgetBase.colDim)
				.align_(\center)
			),
			number = (StaticText()
				.font_(font.boldVariant.size_(font.size + 2))
				.stringColor_(ServerWidgetBase.colText)
				.align_(\center)
			)
		).margins_(0).spacing_(0));
	}

	value_{
		|val|
		number.string = (val.round(0.1).asString + units);
	}
}

GraphCounter {
	var name, units, font, color, <>min, <>max, maxColor, <>minFixed=false, <>maxFixed=false, historySize, reverse,
	<view, heading, number, history, <>span=1, <>round=0.1,
	<>xWarp
	;

	*new {
		|name, units, font, color, min, max, maxColor, historySize=20, reverse=false|
		^super.newCopyArgs(name, units, font, color, min, max, maxColor, min.notNil, max.notNil, historySize, reverse)
		.init;
	}

	init {
		xWarp = ControlSpec(0, 1);

		history = LinkedList.newFrom(0 ! historySize);
		view = UserView().layout_(VLayout(
			heading = (StaticText()
				.string_(name)
				.font_(font)
				.stringColor_(ServerWidgetBase.colDim)
				.align_(\center)
			),
			number = (StaticText()
				.font_(font.boldVariant.size_(font.size + 2))
				.stringColor_(ServerWidgetBase.colText)
				.align_(\center)
				.visible_(units.notNil)
			)
		).margins_(0).spacing_(0))
		.drawFunc_({
			|v|
			var b = v.bounds, size;
			size = history.size()- 1;
			Pen.push();

			Pen.width = 0;
			if (reverse) {
				Pen.scale(b.width, b.height.neg);
				Pen.translate(0, -1);
				Pen.moveTo(0@0);
			} {
				Pen.scale(b.width.neg, b.height.neg);
				Pen.translate(-1, -1);
				Pen.moveTo(0@0);
			};

			history.do {
				|val, i|
				var x, y;
				x = xWarp.map(i / size.asFloat);
				y = (val.linlin(min, max, 0, 1)).min(1).max(0);
				Pen.lineTo(x@y);
			};
			Pen.lineTo(1@0);
			Pen.lineTo(0@0);
			if (maxColor.notNil && (history.first.linlin(min, max, 0, 1) > 0.9)) {
				Pen.fillColor = maxColor.copy.alpha_(0.3);
				Pen.strokeColor = maxColor.blend(Color.white, 0.1).alpha_(0.8);
			} {
				Pen.fillColor = color.copy.alpha_(0.3);
				Pen.strokeColor = color.blend(Color.white, 0.1).alpha_(0.8);
			};
			Pen.draw(3);

			Pen.pop();
		})
		.mouseDownAction_(this.resetMinMax(_));
		this.resetMinMax();
	}

	resetMinMax {
		if (maxFixed.not) { max = -9999999 };
		if (minFixed.not) { min = 9999999 };
		view.refresh();
	}

	clear {
		history = history.collect(0);
		this.value = 0;
	}

	value_{
		|val|
		history.pop();
		history.addFirst(val);

		if ((val < min) && minFixed.not) {
			min = val;
		};
		if ((val > max) && maxFixed.not) {
			max = val;
		};

		number.string = (val.round(round).asString + units);
		view.refresh();
	}
}

VolumeWidget : ServerWidgetBase {
	var view, controller, volumeNum, volumeSlider, muteButton, volSpec, <>height=18;

	view {
		controller = SimpleController(server.volume)
		.put(\amp, {|changer, what, vol|
			{
				volumeNum.value_(vol.round(0.01));
				volumeSlider.value_(volSpec.unmap(vol));
			}.defer
		})
		.put(\mute, {|changer, what, flag|
			{
				muteButton.value_(flag.binaryValue);
			}.defer
		})
		.put(\ampRange, {|changer, what, min, max|
			volSpec = [min, max, \db].asSpec;
			volumeSlider.value_(volSpec.unmap(server.volume.volume));
		});
		volSpec = [server.volume.min, server.volume.max, \db].asSpec;

		view = View().layout_(
			HLayout(
				this.prLabel("vol", 22),
				volumeSlider = (Slider()
					.action_({ |v| server.volume_(volSpec.map(v.value).round(0.1)) })
					.orientation_(\horizontal)
					.fixedHeight_(height)
					.thumbSize_(height - 5)
					.value_(volSpec.unmap(server.volume.volume))
					.toolTip_("the server's volume, in dB")
				),
				volumeNum = (NumberBox()
					.action_({ |v| server.volume_(v.value.round(0.1)) })
					.font_(this.font(11, true))
					.fixedWidth_(48)
					.fixedHeight_(height)
					.value_(server.volume.volume)
				),
				muteButton = (Button()
					.action_({ |v| (v.value == 1).if({ server.mute }, {server.unmute}) })
					.font_(this.font(10))
					.fixedWidth_(46).fixedHeight_(height)
					.canFocus_(false)
					.states_([
						["mute", colText, colPanel],
						["muted", Color.white, colClip]
					])
					.value_(server.volume.isMuted.binaryValue)
				)
			).margins_(0).spacing_(4)
		);
		view.onClose_({ controller.remove() });

		^view;
	}
}

RecordWidget : ServerWidgetBase {
	classvar <recIcon, <stopIcon;
	var view, pathString, <recPath, timeString, timeRoutine, busInput, channelsInput, channelsInputText;

	*initClass {
		Class.initClassTree(ServerWidgetBase);
		recIcon = Image(16).draw({ this.drawRec(14) });
		stopIcon = Image(16).draw({ this.drawStop(16) });
	}

	isRecording {
		^server.recorder.isRecording
	}

	*drawStop {
		| size |
		Pen.stringCenteredIn("▨", Rect(0,1,size,size),
			font: Font(size:14),
			color: colText
		);
	}

	*drawRec {
		| size |
		Pen.stringCenteredIn("●", Rect(0,1,size,size),
			font: Font(size:14),
			color: colRec
		);
	}

	buttonClicked {
		|button|
		var bus, channels;

		bus = busInput.value.asInteger;
		channels = channelsInput.value.asInteger;

		if (this.isRecording) {
			server.stopRecording();
		} {
			server.record(path: this.resolvedRecPath(), bus:bus, numChannels:channels);
		};

		view.refresh;
	}

	resolvedRecFolder {
		var actualPath = recPath ?? { thisProcess.platform.recordingsDir };
		^actualPath;
	}

	resolvedRecPath {
		var timestamp;
		var dir = this.resolvedRecFolder();
		timestamp = Date.localtime.stamp;
		^dir +/+ server.recorder.filePrefix ++ timestamp ++ "." ++ server.recHeaderFormat;
	}

	recPath_{
		|path|
		recPath = path;
		this.updateRecPathString();
	}

	updateRecPathString {
		var path = PathName(this.resolvedRecFolder());
		pathString.string = " ..." +/+ path.folderName +/+ path.fileName;
	}

	view {
		var recButton, items;

		pathString = (DragSink()
			.font_(this.smallFont)
			.stringColor_(colDim)
			.background_(colPanel)
			.fixedHeight_(20)
			.toolTip_("where the file goes - click to open the folder, drop a folder here to change it")
		);
		pathString.mouseUpAction = {
			// openOS is the platform's own: open on macOS, start on Windows, xdg-open on Linux
			this.resolvedRecFolder().openOS;
		};
		pathString.canReceiveDragHandler = { View.currentDrag.asString.pathExists == \folder };
		pathString.receiveDragHandler = {
			this.recPath = View.currentDrag.asString;
		};
		this.updateRecPathString();

		busInput = (NumberBox()
			.font_(this.smallFont)
			.align_(\right)
			.fixedWidth_(20)
			.fixedHeight_(20)
			.toolTip_("the first bus to record")
		);
		busInput.value = 0;

		channelsInputText = StaticText()
			.string_("⁝")
			.font_(this.font(12))
			.stringColor_(colDim)
			.align_(\center)
			.fixedWidth_(6)
			.fixedHeight_(20);
		channelsInput = (NumberBox()
			.font_(this.smallFont)
			.align_(\right)
			.fixedWidth_(20)
			.fixedHeight_(20)
			.toolTip_("how many channels")
		);
		channelsInput.value = server.options.numOutputBusChannels;

		timeString = (TextField()
			.font_(this.monoFont)
			.align_(\right)
			.stringColor_(colText)
			.background_(Color.clear)
			.fixedWidth_(52)
			.fixedHeight_(20)
		);

		recButton = Button()
						.states_([[nil, Color.clear, colPanel]])
						.fixedSize_(20@20)
						.canFocus_(false)
						.toolTip_("record these buses to a file in that folder");
		recButton.icon = recIcon;

		recButton.mouseUpAction = this.buttonClicked(_);

		items = [
			this.prLabel("rec", 22), pathString,
			timeString, HLayout(busInput, channelsInputText, channelsInput).spacing_(0).margins_(0), recButton
		];

		// BusRecorder (the sc-recorder extension) is optional: the button
		// only exists when the class does, so this runs without it.
		if (ServerWidgetBase.hasRecorder) {
			items = items.add(
				Button().fixedSize_(20@20)
					.font_(this.font(9, true))
					.canFocus_(false)
					.states_([["r", colText, colPanel]])
					.toolTip_("Recorder: record any set of buses to disk, and a window to do it from")
					.action_({ ServerWidgetBase.openRecorder })
			);
		};

		view = View().layout_(HLayout(*items).margins_(0).spacing_(4));

		server.signal(\recording).connectTo({
			|server, what, state|
			if (state) {
				recButton.icon = stopIcon;
			} {
				recButton.icon = recIcon;
			}
		}).defer.freeAfter(view);

		server.signal(\recordingDuration).connectTo({
			|server, what, dur|
			timeString.string = (dur).asTimeString(maxDays:0)[3..][0..4];
		}).defer.freeAfter(view);

		view.toFrontAction = view.toFrontAction.addFunc({
			this.updateRecPathString();
		});

		^view
	}
}

// =====================================================================
// MasterFXWidget - the chain of effects on the main out, under the counters
//
//   fx [ open ]  eq on · compressor off
//
// One row: a button that opens the chain in a window of its own - the
// same window `FxChain.master.gui` opens, the eq and the compressor and
// their hint bar in it - and a line that says what the chain is doing
// while that window is closed or behind something else. The button is
// lit while the window is up, and pressing it again closes it; so does
// the `x` key. It works the way the `i` button opens Insight, which is
// the other window this one keeps a light for.
//
// The chain itself is made the first time the window is opened and lives
// on after it is closed: its nodes are on the server, not in the window.
//
// The whole widget only exists when FxChain is compiled in - see
// ServerView:initClass - and everything inside it reaches that class by
// name, so this file compiles without the extension.
// =====================================================================

MasterFXWidget : ServerWidgetBase {
	var view, fxButton, statusText;
	var chain, controller, unitControllers;

	actions {
		^[ServerViewAction("Master fx", 'x', { this.toggle })]
	}

	view {
		fxButton = Button()
			.fixedWidth_(46).fixedHeight_(20)
			.canFocus_(false)
			.font_(this.font(10, true))
			.states_([["fx", colText, colPanel], ["fx", Color.black, colAccent]])
			.toolTip_("the effects on the main out: an eq and a compressor - click for their window, again to close it")
			.action_({ |b| this.toggle(b.value == 1) });
		statusText = StaticText().font_(this.smallFont).stringColor_(colDim);

		view = View().layout_(
			HLayout(fxButton, statusText, nil).margins_(0).spacing_(6)
		);

		// the chain may already exist - made from code, or by an earlier
		// window - in which case the line reads it from the start, and the
		// button is lit if that window is open
		\FxChain.asClass !? { |cls|
			cls.at(("master_" ++ server.name).asSymbol) !? { |c| this.prWatch(c) };
		};
		this.prRefreshStatus;
		this.prRefreshButton;

		view.onClose_({ this.prUnwatch });

		^view
	}

	// -------------------------------------------------------------- open

	toggle {
		|bool|
		if (bool ? this.isOpen.not) { this.open } { this.close };
	}

	// The window is the chain's own, so it comes back where it was left,
	// and closing it leaves everything running. Making the chain is what
	// puts its group on the server, which is why nothing is made until
	// this is pressed.
	open {
		var c = chain ?? { ServerWidgetBase.masterFx(server) };
		if (c.isNil or: { c.isString }) { ^this.prRefreshButton };
		if (chain !== c) { this.prWatch(c) };
		c.tryPerform(\gui);
		this.prRefreshButton;
	}

	close {
		chain !? (_.tryPerform(\closeGui));
		this.prRefreshButton;
	}

	isOpen { ^chain.notNil and: { chain.tryPerform(\guiObj).notNil } }

	// The chain announces \gui whichever way its window went, so the light
	// is right whether the window was opened from here, from the options
	// menu or from code, and goes out when it is closed from its own
	// corner.
	prRefreshButton {
		if (fxButton.isNil or: { fxButton.isClosed }) { ^this };
		fxButton.value = this.isOpen.binaryValue;
	}

	// ------------------------------------------------------------ status

	// one line: each effect and whether it is on, whatever the chain is
	// doing while its window is closed
	prWatch {
		|c|
		this.prUnwatch;
		chain = c;
		controller = SimpleController(chain)
			.put(\units, { { this.prWatchUnits; this.prRefreshStatus }.defer })
			.put(\enabled, { { this.prRefreshStatus }.defer })
			.put(\nodes, { { this.prRefreshStatus }.defer })
			.put(\gui, { { this.prRefreshButton }.defer })
			.put(\freed, {
				{
					chain = nil;
					this.prUnwatch;
					this.prRefreshStatus;
					this.prRefreshButton;
				}.defer
			});
		this.prWatchUnits;
	}

	prWatchUnits {
		unitControllers.do(_.remove);
		unitControllers = chain !? {
			chain.units.collect { |u|
				SimpleController(u)
					.put(\enabled, { { this.prRefreshStatus }.defer })
					.put(\node, { { this.prRefreshStatus }.defer })
			}
		} ? [];
	}

	prUnwatch {
		controller !? (_.remove);
		controller = nil;
		unitControllers.do(_.remove);
		unitControllers = [];
	}

	prRefreshStatus {
		var parts;
		if (statusText.isNil or: { statusText.isClosed }) { ^this };
		if (chain.isNil) {
			statusText.string = "eq and compressor on the main out - not made yet";
			^this
		};
		parts = chain.units.collect { |u|
			"% %".format(u.label.toLower, if (u.enabled) { "on" } { "off" })
		};
		if (chain.enabled.not) { parts = parts.addFirst("chain off") };
		if (chain.units.isEmpty) { parts = ["nothing in the chain"] };
		statusText.string = parts.join(" · ");
	}
}

ScopeWidget : ServerWidgetBase {
	var view, <scopeView,
	inMetersView, <inMeters, inMetersMenu, inChannels, inChannelsActions,
	outMetersView, <outMeters, outMetersMenu, outChannels, outChannelsActions,
	scopeMenu, scopeChannels, scopeStyle, scopeMenu,
	meters, meterActions, prefConnections,
	<synth, levelSynth, levelsName, outresp, bus, rate=\audio, index=0,
	updateFreq=20, cycle=2048, dbList, dBLow, numRMSSamps, style=0, <outBus;

	*new {
		|...args|
		var me;
		me = super.new(*args);
		^me.init
	}

	init {
		super.init();
	}

	initPrefs {
		scopeChannels 	= Preference(\ScopeWidget, \scopeChannels, server.options.outDevice.asSymbol)
							.default_({ server.options.numOutputBusChannels })
							.spec_(ControlSpec(1, 32, step:1));
		scopeStyle	 	= Preference(\ScopeWidget, \scopeStyle)
							.default_({ 0 })
							.spec_(ControlSpec(0, 2, step:1));
		inChannels 		= Preference(\ScopeWidget, \inChannels, server.options.inDevice.asSymbol)
							.default_({ server.options.numInputBusChannels })
							.spec_(ControlSpec(1, 32, step:1));
		outChannels 	= Preference(\ScopeWidget, \outChannels, server.options.outDevice.asSymbol)
							.default_({ server.options.numOutputBusChannels })
							.spec_(ControlSpec(1, 32, step:1));
		dBLow 			= Preference(\ScopeWidget, \dBLow, server.options.outDevice.asSymbol)
							.default_(-60)
							.spec_(ControlSpec(-120, 120));

		prefConnections.free;

		prefConnections = ConnectionList [
			scopeChannels.signal(\value).connectTo(
				this.methodSlot("restartSynth()"),
				this.methodSlot("updateMenus()")
			),
			scopeStyle.signal(\value).connectTo(
				scopeView.methodSlot("style_(value)"),
				this.methodSlot("updateMenus()")
			),
			inChannels.signal(\value).connectTo(
				this.methodSlot("populateInMeters()"),
				this.methodSlot("restartSynth()"),
				this.methodSlot("updateMenus()")
			),
			outChannels.signal(\value).connectTo(
				this.methodSlot("populateOutMeters()"),
				this.methodSlot("restartSynth()"),
				this.methodSlot("updateMenus()")
			),
			dBLow.signal(\value).connectTo(
				{
					meters.do {
						|m|
						m.warning = -3.linlin(this.dBLow, 0, 0, 1);
						m.critical = -0.1.linlin(this.dBLow, 0, 0, 1);
					}
				},
				this.methodSlot("updateMenus()")
			)
		];
	}

	updateMenus {
		meterActions = [-40, -60, -80].collect {
			|db|
			MenuAction(db.asString, {
				|a|
				this.dBLow = db;
			}).checkable_(true).checked_(this.dBLow == db)
		};

		// input meters
		inMetersView.setContextMenuActions(*[
			meterActions,
			MenuAction.separator("Channels"),
			server.options.numInputBusChannels.collect({
				|i|
				i = i + 1;
				MenuAction(i.asString, {
					this.inChannels = i;
				}).checkable_(true).checked_(this.inChannels == i)
			})
		].flatten);

		// output meters
		outMetersView.setContextMenuActions(*[
			meterActions,
			MenuAction.separator("Channels"),
			server.options.numOutputBusChannels.collect({
				|i|
				i = i + 1;
				MenuAction(i.asString, {
					this.outChannels = i
				}).checkable_(true).checked_(this.outChannels == i)
			})
		].flatten);

		// scope
		scopeView.setContextMenuActions(*[
			MenuAction("Separate", 	{ this.scopeStyle = 0 }).checkable_(true).checked_(this.scopeStyle == 0),
			MenuAction("Layered", 	{ this.scopeStyle = 1 }).checkable_(true).checked_(this.scopeStyle == 1),
			MenuAction("XY", 		{ this.scopeStyle = 2 }).checkable_(true).checked_(this.scopeStyle == 2),

			MenuAction.separator("Channels"),

			server.options.numOutputBusChannels.collect({
				|i|
				i = i + 1;
				MenuAction(i.asString, {
					this.scopeChannels = i;
				}).checkable_(true).checked_(this.scopeChannels == i)
			})
		].flatten);
	}

	scopeChannels { ^min(server.options.numOutputBusChannels, scopeChannels.value.asInteger) }
	scopeChannels_{
		|v|
		scopeChannels.value = v;
	}

	scopeStyle { ^scopeStyle.value.asInteger }
	scopeStyle_{
		|v|
		scopeStyle.value = v;
	}

	inChannels { ^min(server.options.numInputBusChannels, inChannels.value.asInteger) }
	inChannels_{
		|v|
		inChannels.value = v;
	}

	outChannels { ^min(server.options.numOutputBusChannels, outChannels.value.asInteger) }
	outChannels_{
		|v|
		outChannels.value = v;
	}

	dBLow { ^dBLow.value.asInteger }
	dBLow_{
		|v|
		dBLow.value = v;
	}

	populateMeters {
		|parent, channels|
		var newMeters;

		parent.removeAll();
		newMeters = channels.collect {
			(LevelIndicator()
				.fixedWidth_(6 - (channels).linlin(2, 8, 0, 3).round(1))
				.minWidth_(2)
				.stepWidth_(1)
				.style_(1)
				.background_(colOff)
				.meterColor_(colMeter)
				.warningColor_(colAccent)
				.criticalColor_(colClip)
				.warning_(-6.dbamp)
				.critical_(-0.1.dbamp)
				.drawsPeak_(true)
			)
		};

		newMeters.do(parent.layout.add(_));
		^newMeters;
	}

	populateInMeters {
		inMeters = this.populateMeters(inMetersView, this.inChannels);
		meters = inMeters ++ outMeters;
	}

	populateOutMeters {
		outMeters = this.populateMeters(outMetersView, this.outChannels);
		meters = inMeters ++ outMeters;
	}

	view {
		var subviews,
			inMenuContext, outMenuContext, scopeContext;

		levelsName = (server.name ++ "OutputWidgetLevels");

		subviews = [
			inMetersView = View().layout_(HLayout().margins_(0).spacing_(0)),
			[scopeView = ScopeView(), stretch:2],
			outMetersView = View().layout_(HLayout().margins_(0).spacing_(0))
		];

		// Insight (the sc-insight extension) is optional: the button only
		// exists when the class does, so this compiles and runs without it.
		if (ServerWidgetBase.hasInsight) {
			subviews = subviews.add(
				View().fixedWidth_(20).layout_(VLayout(
					Button().fixedSize_(20@20)
						.font_(this.font(9, true))
						.canFocus_(false)
						.states_([["i", colText, colPanel]])
						.toolTip_("Insight: spectrum, loudness and image of the main out")
						.action_({ ServerWidgetBase.openInsight(server) }),
					nil
				).margins_(0).spacing_(0))
			);
		};

		view = View().layout_(HLayout(*subviews).margins_(0).spacing_(1));

		this.initPrefs();

		scopeView.server = server;
		scopeView.canFocus = true;
		scopeView.background = colOff;

		ServerTree.add(this, server);
		ServerQuit.add(this, server);

		if (server.serverRunning) {
			this.doOnServerTree();
		};

		scopeView.mouseWheelAction_({ |...args| this.mouseWheelAction(*args) });

		view.signal(\closed).connectTo({
			this.stopSynth();
			ServerTree.remove(this, server);
			ServerQuit.remove(this, server);
		}).oneShot;

		^view;
	}

	mouseWheelAction {
		| v, x, y, mods, xScroll, yScroll |
		if (scopeView.notNil) {
			if (xScroll != 0) {
				cycle = (cycle + (xScroll * 1)).linlin(100, 2048, 100, 2048);
				synth.setCycle(cycle);
			};
			if (yScroll != 0) {
				scopeView.yZoom = (scopeView.yZoom + (yScroll * 0.01)).linlin(1, 100, 1, 100);
			};
		}
	}

	playLevelSynth {
		outBus = Bus.control(server, (this.inChannels + this.outChannels) * 2);
		"Allocated levels outbus: %".format(outBus).postln;
		if (levelSynth.notNil) {
			levelSynth.free;
		};
		if (outresp.notNil) {
			outresp;
		};

		numRMSSamps = server.sampleRate / updateFreq;

		levelSynth = SynthDef(levelsName, {
			|out|
			var sig, imp, peak, max, output;
			sig = [
				// (0..n-1) counts down when n is 0, so no inputs would meter [0, -1]:
				// two channels too many, written past outBus, every out meter shifted
				if (this.inChannels > 0) { SoundIn.ar((0..this.inChannels-1)) } { [] },
				In.ar(0, this.outChannels)
			].flatten;

			imp = Impulse.ar(updateFreq);

			max = RunningMax.ar(sig.abs, Delay1.ar(imp));
			peak = Peak.ar(sig, Delay1.ar(imp)).lag(0, 3);

			output = [max, peak].flop.flat;

			Out.kr(out, output);
			SendReply.ar(imp, "/" ++ levelsName, output);
		}).play(RootNode(server), [\out, outBus], \addToTail);

		outresp = OSCdef(\widgetLevels, {
			|msg, t|
			{
				msg = msg[3..];
				msg.pairsDo({
					|val, peak, i|
					var meter;
					meter = meters[i * 0.5];

					if (meter.notNil) {
						meter.value = val.max(0.0).ampdb.linlin(this.dBLow, 0, 0, 1);
						meter.peakLevel = peak.ampdb.linlin(this.dBLow, 0, 0, 1);
					}
				})
			}.defer;
		}, ("/" ++ levelsName).asSymbol, server.addr).fix;
	}

	startSynth {
		if (synth.isNil) {
			synth = BusScopeSynth(server);
		};
		if (bus.isNil) {
			bus = Bus(rate, index, this.scopeChannels, server);
		};
		if (synth.isPlaying.not) {
			// ServerTree runs this off the GUI thread, so every scopeView
			// (Qt) call has to be deferred or the primitive fails.
			var bufIndex = synth.bufferIndex;
			var colors = this.scopeChannels.collect { |i| this.channelColor(i) };

			{
				if (scopeView.notNil) {
					scopeView.stop();

					scopeView.server = server;
					scopeView.bufnum = bufIndex;
					scopeView.style = 1;
					scopeView.yZoom = 0.9;
					scopeView.waveColors = colors;

					scopeView.start();
				}
			}.defer;

			synth.play(2048, bus, cycle);
			this.playLevelSynth();
		}
	}

	stopSynth {
		// ServerQuit runs this off the GUI thread. Deferring keeps the Qt
		// call from throwing and aborting the cleanup below it.
		{ if (scopeView.notNil) { scopeView.stop() } }.defer;
		synth.free; bus.free; outBus.free; levelSynth.free; outresp.free;
		levelSynth = synth = bus = outBus = outresp = nil;
	}

	restartSynth {
		if (server.serverRunning) {
			this.stopSynth();
			this.startSynth();
		}
	}

	doOnServerTree {
		this.initPrefs();
		this.updateMenus();
		this.populateInMeters();
		this.populateOutMeters();
		this.startSynth();
		this.updateMenus();
	}

	doOnServerQuit {
		this.stopSynth();
	}

	cmdPeriod {
		this.stopSynth();
		this.startSynth();
	}
}

HistoryWidget : ServerWidgetBase {
	var historyResponder, historySynth, counter;
	var cmdName = '/HistoryWidget';

	doOnServerTree {
		|bootingServer|
		if (bootingServer == server) {
			this.start();
		}
	}

	doOnServerQuit {
		|bootingServer|
		if (bootingServer == server) {
			this.stop;
		}
	}

	start {
		var channels = server.options.numOutputBusChannels ?? 2;

		historySynth = SynthDef("ampHistory_%".format(channels).asSymbol, {
			SendPeakRMS.kr(InFeedback.ar(0, channels), 6, 1, cmdName:cmdName);
		}).play(RootNode(server), addAction:\addToTail);

		historyResponder = OSCFunc({
			|msg|
			var peak, rms;
			msg = msg[3..].clump(2).flop;

			peak = msg[0];
			rms = msg[1];

			// { counter.value = peak.maxItem.ampdb.linlin(-60, 0, 0, 1) }.defer;
			{ counter.value = peak.maxItem }.defer;
		}, cmdName);

		historySynth.onFree({
			|freed|
			if (freed == historySynth) {
				historySynth = nil;
			}
		})
	}

	stop {
		historySynth !? { historySynth.free };
		historySynth = nil;

		historyResponder !? { historyResponder.free };
		historyResponder = nil;
	}

	view {
		var view;

		counter = GraphCounter("peak", nil, this.smallFont, colGood, 0.0, 1.0, historySize:160);
		view = counter.view;
		view.background_(colOff);
		view.minHeight_(40);

		if (server.serverBooting.not && server.serverRunning) {
			this.start();
		};

		ServerTree.add(this);
		ServerQuit.add(this);

		view.onClose = { this.stop() };

		^view
	}
}
