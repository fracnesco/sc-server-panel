// =====================================================================
// SPOptions - the server's options, in ServerPanel's colours
//
// What the panel's `o` key and its Options entry open: every ServerOptions
// setting worth changing, in cards by what it is about (the audio device,
// the channels, what the server holds, recording and timing), the rare
// ones folded under "more". A setting you change lights its name; nothing
// is applied until you press apply, or apply and reboot. The bottom line
// says how many changes are waiting and which of them need a reboot.
// Hover over any setting and the hint bar says what it does.
//
//   SPOptions(s);
//   SPOptions(s).set(\memSize, 2 ** 17);   // as if changed in the window
//   SPOptions(s).apply;                     // or .applyAndReboot
//
// One per server, like the scopes. It replaces ServerOptionsGui in the
// panel; that quark is still a dependency and works as before.
// =====================================================================

SPOptions : SPWindow {
	var <pending, rows, statusText, moreButton, moreCard, needsBootText;

	title { ^"options" }
	defaultSize { ^Point(560, 640) }
	hasRunButton { ^false }

	// the top row says what is waiting
	controls {
		statusText = StaticText().font_(this.font(10, true)).stringColor_(SPWidgetBase.colText).minWidth_(170);
		needsBootText = StaticText().font_(this.font(10)).stringColor_(SPWidgetBase.colAccent).minWidth_(260);
		^[statusText, needsBootText]
	}
	defaultHint { ^"hover over a setting for what it does  ·  a changed setting is lit until you apply it  ·  escape closes" }

	// [key, label, kind, extra, hint]; kind: \number [lo, hi, step], \menu
	// [values, names], \check, \text. Keys of the server itself (latency,
	// recording) are set on the server and take effect at once.
	*cards {
		var mb = { |kb| "% MB".format((kb / 1024).round(1).asInteger) };
		var mems = (13..21).collect(2 ** _).collect(_.asInteger);
		^[
			["audio device", [
				[\inDevice, "input device", \menu, nil, "the device the server reads its inputs from - nil is the system's default"],
				[\outDevice, "output device", \menu, nil, "the device the server plays through - nil is the system's default"],
				[\sampleRate, "sample rate", \menu, [[nil, 44100, 48000, 88200, 96000, 192000], ["the device's", "44100", "48000", "88200", "96000", "192000"]],
					"the server's sample rate; the device's is usually right. Inputs and outputs at different rates refuse to boot"],
				[\hardwareBufferSize, "hardware buffer", \menu, [[nil, 32, 64, 128, 256, 512, 1024, 2048], ["the device's", "32", "64", "128", "256", "512", "1024", "2048"]],
					"samples the device hands over at a time: smaller is less latency and more cpu, larger is safer"]
			]],
			["channels", [
				[\numInputBusChannels, "inputs", \number, [0, 1024, 1], "hardware input channels: audio busses after the outputs"],
				[\numOutputBusChannels, "outputs", \number, [0, 1024, 1], "hardware output channels: audio busses from 0"],
				[\numPrivateAudioBusChannels, "private audio busses", \number, [0, 65536, 1], "audio busses for routing inside the server, after the hardware ones"],
				[\numControlBusChannels, "control busses", \number, [0, 1048576, 1], "control rate busses"]
			]],
			["what the server holds", [
				[\memSize, "real-time memory", \menu, [mems, mems.collect(mb)],
					"memory for delay lines, reverbs and grains (memSize, in kB); not for buffers. 128 MB or more for heavy effects"],
				[\maxNodes, "nodes", \number, [64, 1048576, 1], "the most synths and groups at once"],
				[\maxSynthDefs, "synthdefs", \number, [64, 1048576, 1], "the most SynthDefs loaded at once"],
				[\numWireBufs, "wire buffers", \number, [64, 65536, 1], "connections between UGens inside one synth; raise it when a big SynthDef fails to build"],
				[\numBuffers, "buffers", \number, [64, 1048576, 1], "the most buffers at once"],
				[\blockSize, "block size", \menu, [[1, 8, 16, 32, 64, 128, 256, 512], ["1", "8", "16", "32", "64", "128", "256", "512"]],
					"samples per control period: control rate is the sample rate divided by this"]
			]],
			["recording and timing (at once)", [
				[\latency, "latency", \number, [0, 2, 0.01], "seconds the language sends a pattern's events ahead of time: lower is tighter, too low is late messages"],
				[\recChannels, "record channels", \number, [1, 64, 1], "how many channels s.record writes"],
				[\recHeaderFormat, "file format", \menu, [["aiff", "wav", "caf", "w64"], ["aiff", "wav", "caf", "w64"]], "the file type s.record writes"],
				[\recSampleFormat, "sample format", \menu, [["int16", "int24", "int32", "float"], ["16 bit", "24 bit", "32 bit", "float"]], "the sample format s.record writes"]
			]],
			["more", [
				[\verbosity, "verbosity", \number, [-2, 2, 1], "how much the server posts: -2 nothing, 0 normal, 1 and 2 more"],
				[\numRGens, "random generators", \number, [1, 1024, 1], "random number generators for RandSeed / RandID"],
				[\initialNodeID, "first node id", \number, [1, 100000, 1], "where the language starts counting node ids"],
				[\protocol, "protocol", \menu, [[\udp, \tcp], ["udp", "tcp"]], "how the language talks to the server"],
				[\loadDefs, "load synthdefs dir", \check, nil, "load every SynthDef in the synthdefs folder at boot"],
				[\memoryLocking, "lock memory", \check, nil, "keep the server's memory out of swap"],
				[\zeroConf, "zero conf", \check, nil, "announce the server on the network (Bonjour)"],
				[\remoteControlVolume, "remote volume", \check, nil, "let other clients change the volume"],
				[\inputStreamsEnabled, "input streams", \text, nil, "which of the input device's streams to use, as a string of 0 and 1 - empty is all"],
				[\outputStreamsEnabled, "output streams", \text, nil, "which of the output device's streams to use, as a string of 0 and 1 - empty is all"],
				[\restrictedPath, "restricted path", \text, nil, "a folder the server may only read files from - empty is anywhere"]
			] ++ if (Server.program.asString.endsWith("supernova")) {
				[[\threads, "threads", \number, [0, 64, 1], "supernova's dsp threads; 0 is one per core"]]
			} { [] }]
		]
	}

	*serverKeys { ^#[\latency, \recChannels, \recHeaderFormat, \recSampleFormat] }

	prInit {
		|argServer, argKey|
		pending = IdentityDictionary.new;
		rows = IdentityDictionary.new;
		^super.prInit(argServer, argKey)
	}

	// ------------------------------------------------------------- values

	current {
		|key|
		^if (this.class.serverKeys.includes(key)) {
			server.perform(key)
		} {
			server.options.perform(key)
		}
	}

	valueOf { |key| ^if (pending.includesKey(key)) { pending[key] } { this.current(key) } }

	// as if changed in the window: waits for apply
	set {
		|key, value|
		if (value == this.current(key)) { pending.removeAt(key) } { pending[key] = value };
		this.prRefresh;
	}

	revert { pending.clear; this.prRefresh }

	// ServerOptions' own defaults, waiting for apply like any change
	defaults {
		var d = ServerOptions.new;
		this.class.cards.do { |card|
			card[1].do { |r|
				if (this.class.serverKeys.includes(r[0]).not) { this.set(r[0], d.perform(r[0])) }
			}
		};
	}

	needsReboot {
		^server.serverRunning and: { pending.keys.any { |k| this.class.serverKeys.includes(k).not } }
	}

	apply {
		pending.keysValuesDo { |k, v|
			if (this.class.serverKeys.includes(k)) {
				server.perform(k.asSetter, v)
			} {
				server.options.perform(k.asSetter, v)
			};
			"SPOptions: % = %".format(k, v).postln;
		};
		pending.clear;
		this.prRefresh;
	}

	applyAndReboot {
		var reboot = this.needsReboot;
		this.apply;
		if (reboot) { server.reboot }
	}

	// --------------------------------------------------------------- view

	prBody {
		var cards = this.class.cards, column, scroll, bottom;
		var cardOf = { |title, entries|
			var grid = GridLayout().margins_(0).hSpacing_(10).vSpacing_(5);
			grid.addSpanning(StaticText().string_(title).font_(this.font(10, true)).stringColor_(SPWidgetBase.colText), 0, 0, 1, 2);
			entries.do { |r, i|
				var label = StaticText().string_(r[1]).font_(this.font(10)).stringColor_(SPWidgetBase.colDim).fixedWidth_(130);
				var values = if (r[2] == \menu) { r[3] !? { r[3][0].copy } ?? { this.prDevices(r[0]) } };
				var ctl = this.prControl(r, values);
				this.prHintAttach(label, r[4]);
				this.prHintAttach(ctl, r[4]);
				rows[r[0]] = [label, ctl, r, values];
				grid.add(label, i + 1, 0);
				grid.add(ctl, i + 1, 1, \left);
			};
			grid.setColumnStretch(1, 1);
			View().background_(SPWidgetBase.colOff).layout_(grid.margins_(10))
		};
		column = VLayout().spacing_(8).margins_(0);
		cards.do { |c|
			var card = cardOf.(c[0], c[1]);
			if (c[0] == "more") { moreCard = card.visible_(false) };
			column.add(card);
		};
		column.add(nil);
		scroll = ScrollView().hasHorizontalScroller_(false).hasBorder_(false)
			.background_(SPWidgetBase.colBg)
			.canvas_(View().background_(SPWidgetBase.colBg).layout_(column));

		moreButton = Button().fixedHeight_(20).canFocus_(false).font_(this.font(9))
			.states_([["more", SPWidgetBase.colText, SPWidgetBase.colPanel], ["less", Color.black, SPWidgetBase.colAccent]])
			.action_({ |b|
				moreCard.visible = b.value == 1;
				// show what just unfolded
				if (b.value == 1) { { scroll.visibleOrigin = Point(0, 100000) }.defer(0.05) };
			});
		this.prHintAttach(moreButton, "the settings you rarely need: verbosity, protocol, streams, memory locking ...");
		bottom = HLayout(
			moreButton,
			this.prButton("defaults", 60, { this.defaults }, "ServerOptions' own defaults, to apply like any change"),
			this.prButton("help", 40, { HelpBrowser.openHelpFor("ServerOptions") }, "ServerOptions' help page"),
			nil,
			this.prButton("revert", 50, { this.revert }, "forget the changes"),
			this.prButton("apply", 50, { this.apply }, "set the changes; the server's own options take effect at its next boot"),
			Button().fixedHeight_(20).canFocus_(false).font_(this.font(9, true))
				.states_([["apply and reboot", Color.black, SPWidgetBase.colAccent]])
				.action_({ this.applyAndReboot }) !? { |v| this.prHintAttach(v, "set the changes and reboot the server, if a change needs it") }
		).spacing_(6).margins_(0);
		this.prRefresh;
		^View().layout_(VLayout([scroll, stretch: 1], bottom).margins_(0).spacing_(6))
	}

	prControl {
		|r, values|
		var key = r[0], kind = r[2], extra = r[3];
		^switch(kind,
			\number, {
				NumberBox().font_(this.font(10)).align_(\right).fixedWidth_(120).fixedHeight_(20)
					.background_(SPWidgetBase.colPanel).normalColor_(SPWidgetBase.colText).typingColor_(SPWidgetBase.colAccent)
					.clipLo_(extra[0]).clipHi_(extra[1]).step_(extra[2]).scroll_step_(extra[2])
					.decimals_(if (extra[2] < 1) { 2 } { 0 })
					.action_({ |b| this.set(key, if (extra[2] < 1) { b.value } { b.value.asInteger }) })
			},
			\menu, {
				var names = extra !? { extra[1] } ?? { values.collect { |d| d ? "the system's" } };
				PopUpMenu().font_(this.font(10)).fixedHeight_(20).fixedWidth_(if (extra.isNil) { 300 } { 120 })
					.canFocus_(false).items_(names)
					.action_({ |m| this.set(key, values[m.value]) })
			},
			\check, {
				CheckBox().canFocus_(false).action_({ |b| this.set(key, b.value) })
			},
			\text, {
				TextField().font_(this.font(10)).fixedHeight_(20).fixedWidth_(300)
					.background_(SPWidgetBase.colPanel).stringColor_(SPWidgetBase.colText)
					.action_({ |t| this.set(key, if (t.string.isEmpty) { nil } { t.string }) })
			}
		)
	}

	prDevices {
		|key|
		var list = try { ServerOptions.perform(if (key == \inDevice) { \inDevices } { \outDevices }) } ? [];
		^[nil] ++ list
	}

	// every control showing what will be, its label lit when that is a change
	prSyncControls {
		var n = pending.size;
		rows.keysValuesDo { |key, row|
			var label = row[0], ctl = row[1], r = row[2], v = this.valueOf(key);
			if (label.isClosed.not) {
				label.stringColor = if (pending.includesKey(key)) { SPWidgetBase.colAccent } { SPWidgetBase.colDim };
				switch(r[2],
					\number, { if (ctl.hasFocus.not) { ctl.value = v ? 0 } },
					\menu, {
						var i = row[3].indexOfEqual(v);
						if (i.isNil and: { v.notNil }) {
							// a value the list does not have: show it as it is
							row[3] = row[3] ++ [v];
							ctl.items = ctl.items ++ [v.asString];
							i = row[3].size - 1;
						};
						ctl.value = i ? 0;
					},
					\check, { ctl.value = v == true },
					\text, { if (ctl.hasFocus.not) { ctl.string = v ? "" } }
				);
			};
		};
		statusText !? {
			if (statusText.isClosed.not) {
				statusText.string = case
				{ n == 0 } { "% · no changes".format(server.name) }
				{ n == 1 } { "% · 1 change waiting".format(server.name) }
				{ true } { "% · % changes waiting".format(server.name, n) };
				needsBootText.string = if (this.needsReboot) { "reboot to use the new options" } { "" };
			}
		};
	}
}
