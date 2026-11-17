// =====================================================================
// SPWindow - what ServerPanel's own windows are built on
//
// One window per server and kind (asking again brings it to the front),
// the panel's dark palette, a row of controls along the top with a run
// button first, a hint bar along the bottom, space to run and pause, escape to
// close, and hooks that start it again after cmd + period or a reboot and
// stop it when the server quits or the window closes. SPScope,
// SPFreqScope, SPMeter and SPTree are these.
//
// The hint bar says what the pointer is over - a control of the top row,
// a row, a point of a scope - and, over nothing in particular, how to find
// your way in the window (defaultHint). The hints box at the end of the
// top row folds it away, for every window of the family at once:
//
//   SPWindow.showHints = false;
// =====================================================================

SPWindow {
	classvar <all;
	classvar <showHints = true;		// whether the hint bar shows, in every window

	var <server, key, <window, readout, readoutText = "", runButton;
	var <running = true;
	var hintBox, hinted, lastLookup = 0;

	*initClass { all = IdentityDictionary.new }

	// fold the hint bar away, or show it, in every window that is open
	*showHints_ {
		|bool|
		showHints = bool.booleanValue;
		{ all.do { |w| w.prSyncHints } }.defer;
	}

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
	hasRunButton { ^true }					// false for a window with nothing to run
	defaultHint { ^"hover over a control and this bar says what it does" }	// what the bar says over nothing

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
			.action_({ |b| this.running = b.value == 1 });
		this.prHintAttach(runButton, { this.runHint });

		// The hint bar: what is under the pointer, or how to find your way,
		// on a line of its own under the body. Drawn, not a StaticText: a
		// label grows with its text and pushes the controls around (the top
		// row jumped on every hover, 2026-11-06), a line of fixed height that
		// clips what does not fit never moves anything.
		readout = UserView()
			.fixedHeight_(18)
			.minWidth_(10)
			.background_(SPWidgetBase.colOff)
			.drawFunc_({ |v|
				var font = this.font(10), str = readoutText, idle = str.isEmpty;
				if (idle) { str = this.defaultHint };
				Pen.addRect(Rect(0, 0, v.bounds.width, v.bounds.height));
				Pen.clip;
				Pen.stringAtPoint(str, Point(8, 2), font, if (idle) { SPWidgetBase.colDim } { SPWidgetBase.colText });
			});
		readout.visible = showHints;
		hintBox = CheckBox()
			.string_("hints")
			.font_(this.font(9))
			.value_(showHints)
			.canFocus_(false)
			.action_({ |b| this.class.showHints = b.value });
		this.prHintAttach(hintBox, "the bar along the bottom that says what the pointer is over - untick to fold it away");

		top = HLayout(*((if (this.hasRunButton) { [runButton] } { [] }) ++ this.controls ++ [nil, hintBox])).spacing_(6).margins_(0);
		body = this.prBody;
		window.layout = VLayout(top, [body, stretch: 1], readout).margins_(6).spacing_(4);

		// A button ignores a move with no button down and a disabled control
		// hears nothing, so Qt hands the move to the window: the window looks
		// up which hinted control the pointer is over. Over none, the bar goes
		// back to saying how to find your way.
		window.view.mouseOverAction_({ |top, x, y| this.prHintAt(top, x, y); nil });

		window.view.keyDownAction_({ |v, char, mods, unicode, keycode|
			case
			{ char == $  and: { this.hasRunButton } } { this.running = running.not; true }
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
			.action_({ |b| action.value(b.value) }) !? { |v| this.prHintAttach(v, hint) }
	}

	prMenu {
		|items, value, width, action, hint|
		^PopUpMenu()
			.font_(this.font(9))
			.fixedWidth_(width ? 70).fixedHeight_(20)
			.items_(items)
			.value_(value)
			.canFocus_(false)
			.action_({ |m| action.value(m.value) }) !? { |v| this.prHintAttach(v, hint) }
	}

	prButton {
		|str, width, action, hint|
		^Button()
			.fixedWidth_(width).fixedHeight_(20)
			.canFocus_(false)
			.font_(this.font(9))
			.states_([[str, SPWidgetBase.colText, SPWidgetBase.colPanel]])
			.action_({ action.value }) !? { |v| this.prHintAttach(v, hint) }
	}

	// Give a control its hint: a String, or a Function answering one. The
	// bar says it while the pointer is over the control. Answers the control.
	prHintAttach {
		|view, hint|
		if (view.isNil or: { hint.isNil }) { ^view };
		view.acceptsMouseOver = true;
		view.mouseEnterAction = { this.prReadout(hint.value); nil };
		view.mouseOverAction = { this.prReadout(hint.value); nil };
		hinted = hinted.add([view, hint]);
		^view
	}

	// the hint of the smallest hinted control under a point of the window, or none
	prHintAt {
		|top, x, y|
		var p, best, bestArea, origin, now = Main.elapsedTime;
		// a move fires many times a second; a look a frame is plenty
		if ((now - lastLookup) < 0.03) { ^this };
		lastLookup = now;
		origin = try { top.absoluteBounds.origin } ? Point(0, 0);
		p = Point(x, y) + origin;
		hinted = (hinted ? []).reject { |pair| pair[0].isClosed };
		hinted.do { |pair|
			var r = try { pair[0].absoluteBounds };
			if (r.notNil and: { r.containsPoint(p) } and: { try { pair[0].visible } ? false }) {
				if (bestArea.isNil or: { (r.width * r.height) < bestArea }) { best = pair; bestArea = r.width * r.height };
			};
		};
		this.prReadout(best !? { best[1].value } ? "");
	}

	prSyncHints {
		if (this.isOpen) {
			readout.visible = showHints;
			hintBox.value = showHints;
		}
	}

	// what the hint bar says; nothing (nil or "") puts it back to defaultHint
	prReadout {
		|str|
		str = str ? "";
		if (str == readoutText) { ^this };
		readoutText = str;
		if (readout.notNil and: { readout.isClosed.not }) { readout.refresh };
	}

	// the default hint has changed (another view, held still): say the new one
	prHintChanged { { if (readout.notNil and: { readout.isClosed.not }) { readout.refresh } }.defer }

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


