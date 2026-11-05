// =====================================================================
// SPWindow - what ServerPanel's own windows are built on
//
// One window per server and kind (asking again brings it to the front),
// the panel's dark palette, a row of controls along the top with a run
// button first and a readout last, space to run and pause, escape to
// close, and hooks that start it again after cmd + period or a reboot and
// stop it when the server quits or the window closes. SPScope,
// SPFreqScope, SPMeter and SPTree are these.
// =====================================================================

SPWindow {
	classvar <all;

	var <server, key, <window, readout, runButton;
	var <running = true;

	*initClass { all = IdentityDictionary.new }

	// one window per server and kind: asking again brings the open one to the front
	*new {
		|server|
		var k, existing;
		server = server ? Server.default;
		k = (this.name ++ "_" ++ server.name).asSymbol;
		existing = all[k];
		if (existing.notNil and: { existing.isOpen }) {
			existing.front;
			^existing
		};
		existing = super.new.prInit(server, k);
		all[k] = existing;
		^existing
	}

	// --------------------------------------------------- what a subclass says

	title { ^this.subclassResponsibility(thisMethod) }
	defaultSize { ^Point(600, 340) }
	controls { ^[] }						// the widgets of the top row
	prBody { ^View() }						// what fills the window under it
	prStart { }								// what it runs on the server
	prStop { |freeAll = false| }
	prRedraw { }							// draw again, on AppClock
	prResized { }
	runHint { ^"run or pause (space)" }

	// ------------------------------------------------------------------ open

	isOpen { ^window.notNil and: { window.isClosed.not } }
	front { if (this.isOpen) { window.front } }
	close { if (this.isOpen) { window.close } }

	running_ {
		|bool|
		running = bool.booleanValue;
		if (running) { this.prRestart } { this.prStop };
		{ runButton !? { |b| if (b.isClosed.not) { b.value = running.binaryValue } } }.defer;
	}

	prInit {
		|argServer, argKey|
		var size = this.defaultSize, body, top;
		server = argServer;
		key = argKey;

		window = Window(this.title ++ " - " ++ server.name, Rect(200, 200, size.x, size.y), resizable: true);
		window.tryPerform(\autoRememberPosition, key);
		window.acceptsMouseOver = true;
		if (GUI.id == \qt) { window.view.palette = QPalette.dark };
		window.view.background = SPWidgetBase.colBg;

		runButton = Button()
			.fixedSize_(40@20)
			.canFocus_(false)
			.font_(this.font(10, true))
			.states_([["run", SPWidgetBase.colText, SPWidgetBase.colPanel],
				["run", Color.black, SPWidgetBase.colAccent]])
			.value_(running.binaryValue)
			.toolTip_(this.runHint)
			.action_({ |b| this.running = b.value == 1 });

		readout = StaticText()
			.font_(Font(Font.defaultMonoFace, 10))
			.stringColor_(SPWidgetBase.colText)
			.align_(\right)
			.minWidth_(160);

		top = HLayout(*([runButton] ++ this.controls ++ [nil, readout])).spacing_(6).margins_(0);
		body = this.prBody;
		window.layout = VLayout(top, [body, stretch: 1]).margins_(6).spacing_(6);

		window.view.keyDownAction_({ |v, char, mods, unicode, keycode|
			case
			{ char == $  } { this.running = running.not; true }
			{ keycode == 53 or: { unicode == 27 } } { this.close; true }
		});

		window.onClose_({
			ServerTree.remove(this, server);
			ServerQuit.remove(this, server);
			this.prStop(true);
			if (all[key] === this) { all.removeAt(key) };
		});

		ServerTree.add(this, server);
		ServerQuit.add(this, server);
		window.front;
		this.prResized;
		if (server.serverRunning) { this.prRestart };
	}

	// ---------------------------------------------------------------- server

	prRestart {
		if (running and: { server.serverRunning }) {
			this.prStop;
			this.prStart;
		}
	}

	// after a boot and after cmd + period: what was on the server is gone
	doOnServerTree { |s| if (s.isNil or: { s == server }) { this.prRestart; this.prRefresh } }

	doOnServerQuit { |s| if (s.isNil or: { s == server }) { this.prStop(true) } }

	// ---------------------------------------------------------------- the GUI

	font { |size = 11, bold = false| ^Font(Font.defaultSansFace, size, bold) }

	prLabel {
		|str|
		^StaticText().string_(str).font_(this.font(9)).stringColor_(SPWidgetBase.colDim)
	}

	prNumberBox {
		|value, lo, hi, step, width, action, hint|
		^NumberBox()
			.font_(this.font(9))
			.align_(\right)
			.fixedWidth_(width ? 36).fixedHeight_(20)
			.clipLo_(lo).clipHi_(hi).step_(step ? 1).scroll_step_(step ? 1)
			.decimals_(if ((step ? 1) < 1) { 2 } { 0 })
			.value_(value)
			.toolTip_(hint ? "")
			.action_({ |b| action.value(b.value) })
	}

	prMenu {
		|items, value, width, action, hint|
		^PopUpMenu()
			.font_(this.font(9))
			.fixedWidth_(width ? 70).fixedHeight_(20)
			.items_(items)
			.value_(value)
			.canFocus_(false)
			.toolTip_(hint ? "")
			.action_({ |m| action.value(m.value) })
	}

	prButton {
		|str, width, action, hint|
		^Button()
			.fixedWidth_(width).fixedHeight_(20)
			.canFocus_(false)
			.font_(this.font(9))
			.states_([[str, SPWidgetBase.colText, SPWidgetBase.colPanel]])
			.toolTip_(hint ? "")
			.action_({ action.value })
	}

	prReadout { |str| if (readout.notNil and: { readout.isClosed.not }) { readout.string = str ? "" } }

	prWheel { |mods, dx, dy| }

	// put the controls right and draw again, from any thread
	prRefresh { { if (this.isOpen) { this.prSyncControls; this.prRedraw } }.defer }

	prSyncControls { }

	prGridPen {
		|alpha = 0.5|
		Pen.strokeColor = SPWidgetBase.colGrid.copy.alpha_(alpha);
		Pen.width = 1;
	}

	// a label at x, y (its top), aligned by hand: Pen.stringInRect left
	// aligns whatever it is told
	prText {
		|str, x, y, color, align = \left, size = 9|
		var font = this.font(size), w = str.bounds(font).width;
		x = switch(align, \right, { x - w }, \center, { x - (w / 2) }, { x });
		Pen.stringAtPoint(str, Point(x, y), font, color ? SPWidgetBase.colDim);
	}

	sampleRate { ^server.sampleRate ? server.options.sampleRate ? 44100 }
}


