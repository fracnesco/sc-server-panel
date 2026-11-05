// =====================================================================
// SPScope, SPFreqScope - the server's two scopes, in the panel's colours
//
// What `s.scope` and `s.freqscope` open, made again the way the rest of
// ServerPanel is drawn: the dark palette, a row of controls along the
// top, and a grid with its numbers on it - the time and the level for the
// scope, the frequency and the dB for the frequency scope. Point at the
// trace and the top row reads out what is under the pointer.
//
//   SPScope(s);                    // the main out, every channel
//   SPScope(s).index_(8).numChannels_(2);   // any bus
//   SPScope(s).style = 1;          // 0 separate, 1 overlaid, 2 x y
//
//   SPFreqScope(s);                // the main out, left and right summed
//   SPFreqScope(s).source = 1;     // left only (2: right)
//   SPFreqScope(s).freqMode = 0;   // 0 linear, 1 logarithmic
//   SPFreqScope(s).dbRange = 72;   // how far down the bottom of the grid is
//   SPFreqScope(s).fftSize = 8192; // finer at the bottom, slower to move
//
// There is one of each per server: asking again brings the window that is
// already open to the front. The ServerPanel's `s` and `f` keys and its
// options menu open these. Both survive cmd + period and a reboot, and
// free what they hold on the server when the window closes.
//
// In the window: space runs and pauses, escape closes. On the scope the
// mouse wheel zooms the level (up and down) and the time (sideways, or
// with shift held).
//
// They read the server's scope buffers through shared memory, as the stock
// scopes do, so they need a server on this machine.
// =====================================================================

// ---------------------------------------------------------------------
// SPScopeWindow - a ScopeView with a grid drawn over it, which takes the
// mouse: the readout and the wheel are the grid's
// ---------------------------------------------------------------------

SPScopeWindow : SPWindow {
	var <scopeView, <grid, startThread, stopped;

	prDrawGrid { |v| }
	prHover { |v, x, y| ^"" }				// what the readout says at x, y
	runHint { ^"run or pause the scope (space)" }

	prBody {
		var stack;
		scopeView = ScopeView()
			.server_(server)
			.background_(SPWidgetBase.colOff)
			.canFocus_(false);
		grid = UserView()
			.drawFunc_({ |v| this.prDrawGrid(v) })
			.mouseOverAction_({ |v, x, y| this.prReadout(this.prHover(v, x, y)) })
			.mouseMoveAction_({ |v, x, y| this.prReadout(this.prHover(v, x, y)) })
			.mouseLeaveAction_({ this.prReadout("") })
			.mouseWheelAction_({ |v, x, y, mods, dx, dy| this.prWheel(mods, dx, dy); true });
		stack = View().layout_(StackLayout(grid, scopeView).mode_(\stackAll));
		stack.onResize_({ this.prResized; grid.refresh });
		^stack
	}

	prRedraw { grid !? { if (grid.isClosed.not) { grid.refresh } } }

	// Stop the view, then let go of what the server holds - in that order and
	// in one AppClock turn. A ScopeView that paints on after its writer has gone
	// reads freed shared memory and takes sclang down (SIGSEGV in
	// QcScopeShm::paint1D, 2026-11-05, restarting a scope twice in a row).
	// `release` is that letting go; a start waits for it (prAfterStop).
	prStopView {
		|release|
		var done = Condition(false);
		startThread !? { startThread.stop };
		startThread = nil;
		stopped = done;
		{
			if (this.isOpen) { scopeView.stop };
			release.value;
			done.test = true;
			done.signal;
		}.defer;
	}

	// in a Routine: wait until the last stop has let go
	prAfterStop { stopped !? { |c| c.wait } }

}


// ---------------------------------------------------------------------
// SPScope - the oscilloscope: any bus, audio or control, separate,
// overlaid or x y. The grid has a lane per channel, the level at 0.5
// and 1 (moved by the zoom), eight time divisions and the time the
// screen holds.
// ---------------------------------------------------------------------

SPScope : SPScopeWindow {
	classvar <maxFrames = 4096;

	var synth, bus;
	var <index = 0, <rate = \audio, <numChannels, <style = 0, <cycle = 1024, <yZoom = 1;
	var indexBox, rateMenu, channelsBox, styleMenu, cycleBox, zoomBox;

	title { ^"scope" }

	prInit {
		|argServer, argKey|
		numChannels = argServer.options.numOutputBusChannels.max(1);
		^super.prInit(argServer, argKey)
	}

	controls {
		^[
			this.prButton("out", 30, { this.index_(0).numChannels_(server.options.numOutputBusChannels).rate_(\audio) },
				"the hardware outputs"),
			this.prButton("in", 30, {
				this.index_(server.options.numOutputBusChannels).numChannels_(server.options.numInputBusChannels.max(1)).rate_(\audio)
			}, "the hardware inputs"),
			this.prLabel("bus"),
			indexBox = this.prNumberBox(index, 0, 4095, 1, 40, { |v| this.index = v }, "the first bus shown"),
			rateMenu = this.prMenu(["audio", "control"], 0, 70, { |v| this.rate = #[\audio, \control][v] }, "audio or control busses"),
			this.prLabel("ch"),
			channelsBox = this.prNumberBox(numChannels, 1, 32, 1, 30, { |v| this.numChannels = v }, "how many channels"),
			styleMenu = this.prMenu(["separate", "overlay", "x y"], style, 76, { |v| this.style = v },
				"a lane per channel, all in one, or the first two against each other"),
			this.prLabel("frames"),
			cycleBox = this.prNumberBox(cycle, 64, maxFrames, 1, 44, { |v| this.cycle = v },
				"how many samples the screen holds (wheel sideways, or shift + wheel)"),
			this.prLabel("zoom"),
			zoomBox = this.prNumberBox(yZoom, 0.125, 64, 0.25, 40, { |v| this.yZoom = v }, "the level zoom (wheel)")
		]
	}

	// ------------------------------------------------------------- settings

	index_ { |n| index = n.asInteger.max(0); this.prRestart; this.prRefresh }
	rate_ { |r| rate = r.asSymbol; this.prRestart; this.prRefresh }
	numChannels_ { |n| numChannels = n.asInteger.clip(1, 32); this.prRestart; this.prRefresh }

	style_ {
		|n|
		style = n.asInteger.clip(0, 2);
		{ if (this.isOpen) { scopeView.style = style } }.defer;
		this.prRefresh;
	}

	cycle_ {
		|n|
		cycle = n.asInteger.clip(64, maxFrames);
		synth !? { synth.setCycle(cycle) };
		this.prRefresh;
	}

	yZoom_ {
		|z|
		yZoom = z.asFloat.clip(0.125, 64);
		{ if (this.isOpen) { scopeView.yZoom = yZoom } }.defer;
		this.prRefresh;
	}

	prSyncControls {
		indexBox.value = index;
		rateMenu.value = #[\audio, \control].indexOf(rate) ? 0;
		channelsBox.value = numChannels;
		styleMenu.value = style;
		cycleBox.value = cycle;
		zoomBox.value = yZoom;
	}

	// ---------------------------------------------------------------- server

	prStart {
		var colors = numChannels.collect { |i| SPWidgetBase.channelColor(i) };
		startThread = fork {
			var bufIndex;
			this.prAfterStop;
			synth = synth ?? { BusScopeSynth(server) };
			bus = Bus(rate, index, numChannels, server);
			synth.play(maxFrames, bus, cycle);
			bufIndex = synth.bufferIndex;
			{
				if (this.isOpen) {
					scopeView.stop;
					scopeView.server = server;
					scopeView.bufnum = bufIndex;
					scopeView.style = style;
					scopeView.yZoom = yZoom;
					scopeView.waveColors = colors;
					scopeView.start;
				}
			}.defer;
		};
	}

	prStop {
		|freeAll = false|
		var old = synth;
		if (freeAll) { synth = nil };
		this.prStopView({ if (freeAll) { old !? { old.free } } { old !? { old.stop } } });
	}

	// ------------------------------------------------------------------ grid

	// the lanes the scope draws in: one per channel when separate, else one
	prLanes {
		|h|
		var n = if (style == 0) { numChannels } { 1 };
		^n.collect { |i| [i * h / n, h / n] }		// top, height
	}

	prDrawGrid {
		|v|
		var w = v.bounds.width, h = v.bounds.height;
		var ms = cycle / this.sampleRate * 1000;

		if (style == 2) {
			// x y: the axes, and 0.5 and 1 on each, as far as the zoom shows them
			this.prGridPen(0.6);
			Pen.line(Point(w / 2, 0), Point(w / 2, h));
			Pen.line(Point(0, h / 2), Point(w, h / 2));
			Pen.stroke;
			this.prGridPen(0.25);
			[-1, -0.5, 0.5, 1].do { |a|
				var dx = a * yZoom * w / 2, dy = a * yZoom * h / 2;
				if (dx.abs < (w / 2)) { Pen.line(Point(w / 2 + dx, 0), Point(w / 2 + dx, h)) };
				if (dy.abs < (h / 2)) { Pen.line(Point(0, h / 2 - dy), Point(w, h / 2 - dy)) };
			};
			Pen.stroke;
			this.prText("x  %".format(this.prChannelName(0)), 6, 4, SPWidgetBase.channelColor(0));
			this.prText("y  %".format(this.prChannelName(1)), 6, 16, SPWidgetBase.channelColor(1));
			^this
		};

		// eight time divisions
		this.prGridPen(0.18);
		(1..7).do { |i| var x = (i * w / 8).round + 0.5; Pen.line(Point(x, 0), Point(x, h)) };
		Pen.stroke;

		this.prLanes(h).do { |lane, i|
			var top = lane[0], lh = lane[1], cy = top + (lh / 2);
			// the lane's edge, its centre, and the level at 0.5 and 1
			if (i > 0) {
				this.prGridPen(0.7);
				Pen.line(Point(0, top.round + 0.5), Point(w, top.round + 0.5));
				Pen.stroke;
			};
			this.prGridPen(0.55);
			Pen.line(Point(0, cy.round + 0.5), Point(w, cy.round + 0.5));
			Pen.stroke;
			this.prGridPen(0.22);
			[-1, -0.5, 0.5, 1].do { |a|
				var y = cy - (a * yZoom * lh / 2);
				if ((y - top) > 1 and: { (top + lh - y) > 1 }) {
					Pen.line(Point(0, y.round + 0.5), Point(w, y.round + 0.5));
				};
			};
			Pen.stroke;
			// which channel, in its colour; overlaid: all of them in a row
			if (style == 0) {
				this.prText(this.prChannelName(i), 6, top + 3, SPWidgetBase.channelColor(i));
			} {
				numChannels.do { |c| this.prText(this.prChannelName(c), 6 + (c * 34), top + 3, SPWidgetBase.channelColor(c)) };
			};
			// the level at the top of the lane, with the zoom
			if (lh > 40) {
				this.prText(this.prAmpString(1 / yZoom), w - 6, top + 3, nil, \right);
			};
		};

		// what the screen holds
		this.prText("% ms".format(this.prNumber(ms)), w - 6, h - 14, nil, \right);
	}

	prChannelName {
		|i|
		^if (rate == \control) { "c%".format(index + i) } { (index + i).asString }
	}

	prAmpString { |a| ^if (a >= 10) { a.round(1).asString } { a.round(0.01).asString } }

	prNumber { |x| ^if (x >= 100) { x.round(1).asString } { x.round(0.1).asString } }

	prHover {
		|v, x, y|
		var w = v.bounds.width, h = v.bounds.height, lane, i, a, t;
		if (style == 2) {
			^"x %  y %".format(
				this.prAmpString((x - (w / 2)) / (w / 2) / yZoom),
				this.prAmpString(((h / 2) - y) / (h / 2) / yZoom))
		};
		i = if (style == 0) { (y / h * numChannels).floor.asInteger.clip(0, numChannels - 1) } { 0 };
		lane = this.prLanes(h)[i];
		a = ((lane[0] + (lane[1] / 2)) - y) / (lane[1] / 2) / yZoom;
		t = x / w * cycle / this.sampleRate * 1000;
		^"%%  %  ·  % ms".format(
			if (style == 0) { "ch " } { "" },
			if (style == 0) { this.prChannelName(i) } { "" },
			this.prAmpString(a),
			this.prNumber(t))
	}

	// up and down: the level; sideways or with shift: the time
	prWheel {
		|mods, dx, dy|
		if (mods.isShift and: { dx == 0 }) { dx = dy; dy = 0 };
		if (dx != 0) { this.cycle = (cycle * (1.02 ** (dx.abs.min(20) * dx.sign.neg))).round };
		if (dy != 0) { this.yZoom = yZoom * (1.02 ** (dy.sign * dy.abs.min(20))) };
	}
}


// ---------------------------------------------------------------------
// SPFreqScope - the spectrum of a stereo bus: left and right summed, or
// one of them. The analysis is the stock frequency scope's, with the fft
// size free and the level kept the same whatever the size. The grid
// has the frequencies (20, 50, 100 ... on a logarithmic scale, every
// kHz on a linear one) and the dB.
// ---------------------------------------------------------------------

SPFreqScope : SPScopeWindow {
	classvar <fftSizes = #[2048, 4096, 8192], <dbRanges = #[48, 72, 96, 120, 144];

	// How many points one sweep of the spectrum is drawn with. The scope view
	// can squeeze frames into a pixel but never spread one over several (its
	// xZoom stops at 1), so a sweep narrower than the window was drawn short:
	// at fft 2048 the stock 512 frames put 5 kHz at 4 kHz in a 628 pixel scope
	// (measured 2026-11-05). 2048 fills any window up to 2048 pixels wide.
	classvar <frames = 2048;

	var synth, scopeBuffer;
	var <inBus = 0, <source = 0, <freqMode = 1, <dbRange = 96, <fftSize = 4096;
	var busBox, sourceMenu, modeMenu, rangeMenu, fftMenu;

	title { ^"freq scope" }
	defaultSize { ^Point(640, 340) }

	// the analysis, stock's apart from the two channels and the level, which
	// is the same at any fft size. mode 0 linear, 1 logarithmic
	*synthDef {
		|mode|
		^SynthDef(("sp_freqscope" ++ mode).asSymbol, {
			|in = 0, fftBufSize = 4096, scopebufnum = 1, rate = 4, dbFactor = 0.02, source = 0|
			var phase = 1 - (rate * fftBufSize.reciprocal);
			var fftbuf = LocalBuf(fftBufSize, 1);
			var stereo = In.ar(in, 2);
			var signal = Select.ar(source, [stereo.sum * 0.5, stereo[0], stereo[1]]);
			var mul = 0.00285 * 2048 / fftBufSize;
			var chain, phasor, n;
			chain = FFT(fftbuf, signal, hop: 0.75, wintype: 1);
			chain = PV_MagSmear(chain, 1);
			if (mode == 0) {
				n = (BufSamples.ir(fftbuf) - 2) * 0.5;
				phasor = LFSaw.ar(rate / BufDur.ir(fftbuf), phase, n, n + 2);
			} {
				n = BufSamples.ir(fftbuf) * 0.5;
				phasor = n.pow(LFSaw.ar(rate / BufDur.ir(fftbuf), phase, 0.5, 0.5)) * 2;
			};
			phasor = phasor.round(2);	// the evens are the magnitudes
			ScopeOut2.ar(((BufRd.ar(1, fftbuf, phasor, 1, 1) * mul).ampdb * dbFactor) + 1,
				scopebufnum, fftBufSize / rate);
		}, [\kr, \ir, \ir, \ir, \kr, \kr])
	}

	controls {
		^[
			this.prLabel("bus"),
			busBox = this.prNumberBox(inBus, 0, 4095, 1, 40, { |v| this.inBus = v },
				"the first of the two busses analysed (0: the main out)"),
			sourceMenu = this.prMenu(["L + R", "L", "R"], source, 60, { |v| this.source = v },
				"both channels summed, or one of them"),
			modeMenu = this.prMenu(["log", "lin"], 1 - freqMode, 50, { |v| this.freqMode = 1 - v },
				"frequencies on a logarithmic or a linear scale"),
			this.prLabel("range"),
			rangeMenu = this.prMenu(dbRanges.collect { |d| "% dB".format(d) }, dbRanges.indexOfEqual(dbRange), 64,
				{ |v| this.dbRange = dbRanges[v] }, "how far down the bottom of the grid is"),
			this.prLabel("fft"),
			fftMenu = this.prMenu(fftSizes.collect(_.asString), fftSizes.indexOfEqual(fftSize), 60,
				{ |v| this.fftSize = fftSizes[v] }, "bigger: finer in the low end, slower to follow")
		]
	}

	// ------------------------------------------------------------- settings

	inBus_ { |n| inBus = n.asInteger.max(0); synth !? { synth.set(\in, inBus) }; this.prRefresh }
	source_ { |n| source = n.asInteger.clip(0, 2); synth !? { synth.set(\source, source) }; this.prRefresh }
	freqMode_ { |n| freqMode = n.asInteger.clip(0, 1); this.prRestart; this.prRefresh }
	dbRange_ { |db| dbRange = db.asFloat.max(6); synth !? { synth.set(\dbFactor, 2 / dbRange) }; this.prRefresh }
	fftSize_ { |n| fftSize = n.asInteger.nextPowerOfTwo.clip(512, 16384); this.prRestart; this.prResized; this.prRefresh }

	prSyncControls {
		busBox.value = inBus;
		sourceMenu.value = source;
		modeMenu.value = 1 - freqMode;
		rangeMenu.value = dbRanges.indexOfEqual(dbRange) ? rangeMenu.value;
		fftMenu.value = fftSizes.indexOfEqual(fftSize) ? fftMenu.value;
	}

	// ---------------------------------------------------------------- server

	prStart {
		var def = this.class.synthDef(freqMode);
		if (server.isLocal.not) {
			"SPFreqScope: % is not on this machine - the scope reads shared memory".format(server.name).warn;
			^this
		};
		startThread = fork {
			this.prAfterStop;
			scopeBuffer = scopeBuffer ?? { ScopeBuffer.alloc(server) };
			def.send(server);
			server.sync;
			synth = Synth.tail(RootNode(server), def.name, [
				\in, inBus, \fftBufSize, fftSize, \scopebufnum, scopeBuffer.index,
				\rate, this.prSweepRate, \dbFactor, 2 / dbRange, \source, source
			]);
			{
				if (this.isOpen) {
					scopeView.stop;
					scopeView.server = server;
					scopeView.bufnum = scopeBuffer.index;
					scopeView.style = 1;
					scopeView.fill = false;
					scopeView.waveColors = [SPWidgetBase.colMeter];
					this.prResized;
					scopeView.start;
				}
			}.defer;
		};
	}

	prStop {
		|freeAll = false|
		var old = synth, buf = if (freeAll) { scopeBuffer };
		synth = nil;
		if (freeAll) { scopeBuffer = nil };
		this.prStopView({
			old !? { if (server.serverRunning) { server.sendBundle(nil, ['/error', -1], ['/n_free', old.nodeID], ['/error', -2]) } };
			buf !? { buf.free };
		});
	}

	doOnServerQuit { |s| if (s.isNil or: { s == server }) { scopeBuffer = nil; synth = nil; this.prStop } }

	// sweeps per fft buffer, so that a sweep is `frames` long
	prSweepRate { ^fftSize / frames }

	// the scope shows one sweep across its width
	prResized {
		{
			if (this.isOpen) {
				scopeView.xZoom = (frames / scopeView.bounds.width.max(1)).max(1);
			}
		}.defer;
	}

	// ------------------------------------------------------------- the scale

	nyquist { ^this.sampleRate / 2 }

	// where a frequency is across the width, 0 to 1, and back
	freqToX {
		|f|
		var half = fftSize / 2;
		^if (freqMode == 1) {
			(log(f / this.nyquist) / log(half)) + 1
		} {
			f / this.nyquist
		}
	}

	xToFreq {
		|p|
		var half = fftSize / 2;
		^if (freqMode == 1) { half ** (p - 1) * this.nyquist } { p * this.nyquist }
	}

	// 20, 500, 1k, 2.5k, 10k
	prFreqString {
		|f|
		var k = f / 1000;
		^case
		{ f < 1000 } { f.round(1).asInteger.asString }
		{ k.frac < 0.05 } { "%k".format(k.round(1).asInteger) }
		{ true } { "%k".format(k.round(0.1)) }
	}

	prDrawGrid {
		|v|
		var w = v.bounds.width, h = v.bounds.height, nyq = this.nyquist;
		var majors, minors, step;

		if (freqMode == 1) {
			minors = [1, 10, 100, 1000, 10000].collect { |d| (1..9) * d }.flat;
			majors = #[20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000];
		} {
			minors = (1..(nyq / 1000).floor.asInteger) * 1000;
			step = if (w > 500) { 2000 } { 4000 };
			majors = (1..(nyq / step).floor.asInteger) * step;
		};
		minors = minors.select { |f| f < nyq and: { this.freqToX(f) > 0.02 } };
		majors = majors.select { |f| f < nyq and: { this.freqToX(f) > 0.02 } };

		// frequencies
		this.prGridPen(0.18);
		minors.do { |f| var x = (this.freqToX(f) * w).round + 0.5; Pen.line(Point(x, 0), Point(x, h)) };
		Pen.stroke;
		this.prGridPen(0.45);
		majors.do { |f| var x = (this.freqToX(f) * w).round + 0.5; Pen.line(Point(x, 0), Point(x, h)) };
		Pen.stroke;
		majors.do { |f|
			var str = this.prFreqString(f), x = this.freqToX(f) * w, half = str.bounds(this.font(9)).width / 2;
			if ((x + half) < (w - 2)) { this.prText(str, x, h - 14, nil, \center) };
		};

		// dB: 0 at the top, -dbRange at the bottom
		step = case { dbRange <= 48 } { 6 } { dbRange <= 96 } { 12 } { true } { 24 };
		this.prGridPen(0.35);
		(1..(dbRange / step).floor.asInteger).do { |i|
			var y = (i * step / dbRange * h).round + 0.5;
			if (y < (h - 2)) { Pen.line(Point(0, y), Point(w, y)) };
		};
		Pen.stroke;
		(0..(dbRange / step).floor.asInteger).do { |i|
			var y = i * step / dbRange * h;
			if (y < (h - 26)) { this.prText((i * step).neg.asString, 4, y + 2) };
		};
		this.prText(#["L + R", "L", "R"][source] ++ "  bus " ++ inBus, w - 6, 3, nil, \right);
	}

	prHover {
		|v, x, y|
		var f = this.xToFreq(x / v.bounds.width).max(1), db = (y / v.bounds.height * dbRange).neg;
		var midi = f.cpsmidi, near = midi.round, cents = ((midi - near) * 100).round.asInteger;
		var names = #["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
		^"% Hz  %%  %c  ·  % dB".format(
			if (f >= 1000) { (f / 1000).round(0.01).asString ++ "k" } { f.round(1).asInteger },
			names[near.asInteger % 12], (near.asInteger div: 12) - 1,
			if (cents >= 0) { "+" ++ cents } { cents },
			db.round(0.1))
	}
}
