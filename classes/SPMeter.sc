// =====================================================================
// SPMeter - the server's level meters, in ServerPanel's colours
//
// What `s.meter` opens, made again: every input and output as a bar with
// its rms as the body and its peak as a line, a peak hold, a clip light
// that stays on until you click, the held peak in dB under each bar, and
// one dB scale beside them all. Point at a bar and the top row reads out
// its peak and rms.
//
//   SPMeter(s);
//   SPMeter(s).dbLow = -80;        // the bottom of the scale
//   SPMeter(s).holdTime = inf;     // hold the highest peak until a click
//   SPMeter(s).reset;              // the holds and the clip lights
//
// One per server, like the scopes; ServerPanel's `l` key opens it. Its
// synth sits at the tail of the root node, so it reads the outputs after
// everything, the master fx chain included.
// =====================================================================

SPMeter : SPWindow {
	classvar <replyRate = 20, <maxChannels = 32, idCounter = 0;
	classvar <dbRanges = #[-48, -60, -80, -96], <holdTimes = #[1, 3, inf];

	var <dbLow = -60, <holdTime = 3;
	var numIns, numOuts, levels, view, synth, resp, startThread, replyID;
	var barW = 12, slot = 16;		// worked out from the view's width at each draw
	var rangeMenu, holdMenu;

	// a bar is as wide as the view allows, within these, gap apart, in a
	// column after the scale
	*minBarW { ^10 }
	*maxBarW { ^56 }
	*gap { ^4 }
	*scaleW { ^30 }
	*groupGap { ^16 }
	*topH { ^30 }
	*bottomH { ^30 }

	title { ^"levels" }

	defaultSize {
		var w = this.class.scaleW + 16 + (this.prBarCount * 22) + this.prGroupGap;
		^Point(w.clip(300, 1200), 300)
	}

	runHint { ^"run or pause the meters (space)" }

	prInit {
		|argServer, argKey|
		numIns = argServer.options.numInputBusChannels.clip(0, maxChannels);
		numOuts = argServer.options.numOutputBusChannels.clip(0, maxChannels);
		idCounter = idCounter + 1;
		replyID = idCounter;
		this.prClearLevels;
		^super.prInit(argServer, argKey)
	}

	prBarCount { ^numIns + numOuts }

	prGroupGap { ^if (numIns > 0 and: { numOuts > 0 }) { this.class.groupGap } { 0 } }

	// the bars share the width: each gets a slot, a bar and a gap
	prLayout {
		|w|
		var avail = w - this.class.scaleW - 12 - this.prGroupGap;
		barW = ((avail / this.prBarCount.max(1)) - this.class.gap).clip(this.class.minBarW, this.class.maxBarW).floor;
		slot = barW + this.class.gap;
	}

	// one Event per bar: what the last reply said, and what is held
	prClearLevels {
		levels = this.prBarCount.collect { (peak: 0, rms: 0, hold: 0, holdAt: 0, max: 0, clip: false) };
	}

	controls {
		^[
			this.prLabel("range"),
			rangeMenu = this.prMenu(dbRanges.collect { |d| "% dB".format(d) }, dbRanges.indexOfEqual(dbLow), 64,
				{ |v| this.dbLow = dbRanges[v] }, "the bottom of the scale"),
			this.prLabel("hold"),
			holdMenu = this.prMenu(["1 s", "3 s", "until click"], holdTimes.indexOfEqual(holdTime), 84,
				{ |v| this.holdTime = holdTimes[v] }, "how long a peak is held"),
			this.prButton("reset", 44, { this.reset }, "let go of the held peaks and the clip lights (or click the meters)")
		]
	}

	// ------------------------------------------------------------- settings

	dbLow_ { |db| dbLow = db.asFloat.min(-12); this.prRefresh }
	holdTime_ { |t| holdTime = t; this.prRefresh }

	// the held peaks, the numbers under the bars and the clip lights, all let go
	reset {
		levels.do { |l| l[\hold] = l[\peak]; l[\max] = l[\peak]; l[\clip] = false };
		this.prRefresh;
	}

	prSyncControls {
		rangeMenu.value = dbRanges.indexOfEqual(dbLow) ? rangeMenu.value;
		holdMenu.value = holdTimes.indexOfEqual(holdTime) ? holdMenu.value;
	}

	// ---------------------------------------------------------------- server

	synthDef {
		^SynthDef(("sp_meter_%_%".format(numIns, numOuts)).asSymbol, {
			var ins = if (numIns > 0) { SoundIn.ar((0..numIns - 1)) } { [] };
			var outs = In.ar(0, numOuts);
			SendPeakRMS.kr(ins.asArray ++ outs.asArray, replyRate, 1.5, '/sp_meter', replyID);
		})
	}

	prStart {
		var def = this.synthDef;
		if (this.prBarCount == 0) { ^this };
		resp = OSCFunc({ |msg| this.prReply(msg) }, '/sp_meter', server.addr, argTemplate: [nil, replyID]);
		startThread = fork {
			def.send(server);
			server.sync;
			synth = Synth.tail(RootNode(server), def.name);
		};
	}

	prStop {
		|freeAll = false|
		startThread !? { startThread.stop };
		startThread = nil;
		resp !? { resp.free };
		resp = nil;
		synth !? { if (server.serverRunning) { server.sendBundle(nil, ['/error', -1], ['/n_free', synth.nodeID], ['/error', -2]) } };
		synth = nil;
		levels.do { |l| l[\peak] = 0; l[\rms] = 0 };
		this.prRefresh;
	}

	prReply {
		|msg|
		var now = Main.elapsedTime;
		msg[3..].clump(2).do { |pr, i|
			var l = levels[i];
			if (l.notNil) {
				l[\peak] = pr[0];
				l[\rms] = pr[1];
				if (pr[0] >= l[\hold] or: { (now - l[\holdAt]) > holdTime }) { l[\hold] = pr[0]; l[\holdAt] = now };
				l[\max] = max(l[\max], pr[0]);
				if (pr[0] >= 1) { l[\clip] = true };
			}
		};
		{ this.prRedraw }.defer;
	}

	// ------------------------------------------------------------------ view

	prBody {
		view = UserView()
			.background_(SPWidgetBase.colOff)
			.drawFunc_({ |v| this.prDraw(v) })
			.mouseOverAction_({ |v, x, y| this.prReadout(this.prHover(v, x, y)) })
			.mouseLeaveAction_({ this.prReadout("") })
			.mouseDownAction_({ this.reset; true });
		^view
	}

	prRedraw { view !? { if (view.isClosed.not) { view.refresh } } }

	// where bar i starts across the view
	prBarX {
		|i|
		var x = this.class.scaleW + 4 + (i * slot);
		if (i >= numIns and: { numIns > 0 }) { x = x + this.class.groupGap };
		^x
	}

	prName { |i| ^if (i < numIns) { "in %".format(i + 1) } { "out %".format(i - numIns) } }

	prShortName { |i| ^if (i < numIns) { (i + 1).asString } { (i - numIns).asString } }

	// a level as 0 at the bottom of the scale, 1 at 0 dBFS
	prFrac { |amp| ^((amp.max(1e-9).ampdb - dbLow) / dbLow.neg).clip(0, 1) }

	prDbString {
		|amp|
		var db = amp.max(1e-9).ampdb;
		^if (db < dbLow) { "-inf" } { db.round(0.1).asString }
	}

	prTicks {
		^#[0, -3, -6, -9, -12, -18, -24, -30, -40, -50, -60, -70, -80, -90].select { |d|
			(d >= dbLow) and: { dbLow > -70 or: { d % 10 == 0 or: { d > -12 } } }
		}
	}

	prDraw {
		|v|
		var w = v.bounds.width, h = v.bounds.height, top = this.class.topH, bottom = h - this.class.bottomH;
		var span = bottom - top, bw, right;
		var warnY = bottom - (span * this.prFrac(-6.dbamp)), clipY = bottom - (span * this.prFrac(-1.dbamp));
		this.prLayout(w);
		bw = barW;
		right = if (this.prBarCount > 0) { this.prBarX(this.prBarCount - 1) + bw + 2 } { w - 4 };

		// the scale, its lines across every bar
		this.prTicks.do { |d|
			var y = (bottom - (span * this.prFrac(d.dbamp))).round + 0.5;
			Pen.strokeColor = SPWidgetBase.colGrid.copy.alpha_(if (d == 0) { 0.7 } { 0.3 });
			Pen.width = 1;
			Pen.line(Point(this.class.scaleW, y), Point(right, y));
			Pen.stroke;
			this.prText(d.asString, this.class.scaleW - 4, y - 6, nil, \right);
		};

		// which group is which
		if (numIns > 0) { this.prText("in", this.prBarX(0), 2) };
		if (numOuts > 0) { this.prText("out", this.prBarX(numIns), 2) };

		levels.do { |l, i|
			var x = this.prBarX(i), rmsY = bottom - (span * this.prFrac(l[\rms]));
			var peakY = bottom - (span * this.prFrac(l[\peak])), holdY = bottom - (span * this.prFrac(l[\hold]));
			// the well, then the rms in three colours, as the panel's meters
			Pen.fillColor = SPWidgetBase.colBg;
			Pen.fillRect(Rect(x, top, bw, span));
			[[bottom, warnY, SPWidgetBase.colMeter], [warnY, clipY, SPWidgetBase.colAccent], [clipY, top, SPWidgetBase.colClip]].do { |seg|
				var lo = seg[0], hi = max(seg[1], rmsY);
				if (hi < lo) { Pen.fillColor = seg[2]; Pen.fillRect(Rect(x, hi, bw, lo - hi)) };
			};
			// the peak, and what is held
			if (l[\peak] > 0) {
				Pen.fillColor = SPWidgetBase.colPeak.copy.alpha_(0.8);
				Pen.fillRect(Rect(x, peakY.round - 1, bw, 2));
			};
			if (l[\hold] > 0 and: { holdY < (bottom - 1) }) {
				Pen.fillColor = if (l[\hold] >= 1) { SPWidgetBase.colClip } { SPWidgetBase.colAccent };
				Pen.fillRect(Rect(x, holdY.round - 1, bw, 2));
			};
			// the clip light
			Pen.fillColor = if (l[\clip]) { SPWidgetBase.colClip } { SPWidgetBase.colPanel };
			Pen.fillRect(Rect(x, top - 9, bw, 6));
			// the channel, and the highest peak since the last reset
			this.prText(this.prShortName(i), x + (bw / 2), bottom + 3, nil, \center);
			this.prText(this.prMaxString(l[\max]), x + (bw / 2), bottom + 15,
				if (l[\max] >= 1) { SPWidgetBase.colClip } { SPWidgetBase.colText }, \center, 8);
		};
		if (this.prBarCount == 0) { this.prText("this server has no inputs and no outputs", 10, 10) };
	}

	// the highest peak, short enough to sit under a bar
	prMaxString {
		|amp|
		var db = amp.max(1e-9).ampdb;
		^case
		{ db < dbLow } { "-" }
		{ db > -10 } { db.round(0.1).asString }
		{ true } { db.round(1).asInteger.asString }
	}

	prHover {
		|v, x, y|
		var i = this.prBarCount.collect(_.value).detect { |i| x >= this.prBarX(i) and: { x < (this.prBarX(i) + slot) } };
		var l;
		if (i.isNil) { ^"" };
		l = levels[i];
		^"%  peak %  rms %  max %".format(this.prName(i), this.prDbString(l[\peak]), this.prDbString(l[\rms]), this.prDbString(l[\max]))
	}
}
