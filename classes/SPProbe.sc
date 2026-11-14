// =====================================================================
// SPProbe - the level on any set of busses, and a way to listen to one
//
// What SPTree measures with. A probe reads audio bus channels at the tail
// of the root node - after everything that writes them in the block - and
// answers ten times a second with each one's peak and whether it carried a
// NaN, an inf or a denormal. Sixteen channels a synth (sp_probe16), as many
// synths as the list needs, so a stock server's 64 wire buffers are enough.
// Control busses are asked for with /c_get.
//
//   p = SPProbe(s);
//   p.setBusses([0, 1, 16, 17], [3]);   // audio channels, control channels
//   p.level(16);                        // its peak, held and let fall
//   p.bad(16);                          // 0 fine, 1 NaN, 2 inf, 3 denormal
//   p.kval(3);                          // a control bus's value
//   p.listen(16, 2);                    // hear bus 16-17 on the outputs
//   p.stopListening;
//   p.free;
//
// The probes and the listener are the observer's own synths: SPTree leaves
// them out of what it shows.
// =====================================================================

SPProbe {
	classvar <slots = 16, <maxSynths = 16, <>replyRate = 10, idCounter = 1000;
	classvar <>listenOut = 0;		// where listen plays: the first of two busses
	classvar <>listenAmp = 0.5;

	var <server, <>action;				// action: called on AppClock's side of things after each batch of replies
	var audio, control, synths, resp, kresp, levels, raws, bads, kvals, baseID;
	var <listening, listenSynth, startThread, wanted, alive = false;

	*new { |server, action| ^super.newCopyArgs(server ? Server.default, action).init }

	*isOwn { |def| var s = def.asString; ^s.beginsWith("sp_probe") or: { s.beginsWith("sp_listen") } }

	init {
		audio = [];
		control = [];
		synths = [];
		levels = IdentityDictionary.new;
		raws = IdentityDictionary.new;
		bads = IdentityDictionary.new;
		kvals = IdentityDictionary.new;
		idCounter = idCounter + 100;
		baseID = idCounter;
	}

	*probeDef {
		^SynthDef(\sp_probe16, {
			|replyID = 0|
			var busses = \busses.kr(-1 ! slots);
			var on = busses >= 0;
			var sig = (In.ar(busses.max(0)).asArray.flat * on).asArray;
			var bad = sig.collect { |x| CheckBadValues.ar(x, 0, 0) };
			// a NaN times 0 is a NaN: what is bad is replaced, not scaled
			var clean = sig.collect { |x, i| Select.ar(bad[i] > 0, [x, DC.ar(0)]) };
			var imp = Impulse.ar(replyRate), reset = Delay1.ar(imp);
			var peak = RunningMax.ar(clean.abs, reset), worst = RunningMax.ar(bad, reset);
			SendReply.ar(imp, '/sp_probe', peak ++ worst, replyID);
		})
	}

	*listenDef {
		|channels|
		^SynthDef(("sp_listen" ++ channels).asSymbol, {
			|bus = 0, out = 0, amp = 0.5, gate = 1|
			var sig = In.ar(bus, channels), env = EnvGen.kr(Env.asr(0.05, 1, 0.1), gate, doneAction: 2);
			sig = if (channels == 1) { sig ! 2 } { sig.asArray.keep(2) };
			Out.ar(out, (sig * amp.lag(0.05) * env).clip2(1));
		})
	}

	// ---------------------------------------------------------------- what to read

	// The audio and control bus channels to measure. More audio channels than
	// fit (slots * maxSynths) are left out from the end: put first what matters.
	setBusses {
		|audioIndices, controlIndices|
		audioIndices = (audioIndices ? []).asArray.keep(slots * maxSynths);
		control = (controlIndices ? []).asArray.keep(256);
		if (audioIndices != audio) {
			audio = audioIndices;
			if (alive) { this.prApply };
		};
	}

	audioBusses { ^audio }

	// a bus channel's peak: nil when it is not measured
	level { |index| ^levels[index] }

	// the last reply's peak, not held
	raw { |index| ^raws[index] }

	bad { |index| ^bads[index] ? 0 }

	kval { |index| ^kvals[index] }

	levelsCopy { ^levels.copy }

	// ---------------------------------------------------------------- running

	start {
		if (alive or: { server.serverRunning.not }) { ^this };
		alive = true;
		resp = OSCFunc({ |msg| this.prReply(msg) }, '/sp_probe', server.addr);
		kresp = OSCFunc({ |msg| msg[1..].pairsDo { |k, v| kvals[k] = v } }, '/c_set', server.addr);
		startThread = fork {
			this.class.probeDef.send(server);
			[1, 2].do { |n| this.class.listenDef(n).send(server) };
			server.sync;
			wanted = true;
			this.prApply;
		};
	}

	stop {
		alive = false;
		wanted = false;
		startThread !? { startThread.stop };
		startThread = nil;
		resp !? { resp.free };
		kresp !? { kresp.free };
		resp = kresp = nil;
		this.stopListening;
		if (server.serverRunning) {
			synths.do { |id| server.sendBundle(nil, ['/error', -1], ['/n_free', id], ['/error', -2]) };
		};
		synths = [];
	}

	free { this.stop; levels.clear; raws.clear; bads.clear; kvals.clear }

	// after cmd + period or a reboot: the synths are gone
	restart {
		synths = [];
		listenSynth = nil;
		listening = nil;
		if (alive) { alive = false; resp !? { resp.free }; kresp !? { kresp.free }; this.start };
	}

	// ask for the control busses' values; called at the tree's own pace
	poll {
		if (alive and: { control.notEmpty } and: { server.serverRunning }) {
			server.sendMsg(*(['/c_get'] ++ control));
		}
	}

	// keep the probes last in the root node, so they read what was written this block
	toTail {
		if (alive and: { server.serverRunning }) {
			// the listener first: the probes read what it plays, so they come after it
			listenSynth !? { |id| server.sendBundle(nil, ['/error', -1], ['/g_tail', 0, id], ['/error', -2]) };
			synths.do { |id| server.sendBundle(nil, ['/error', -1], ['/g_tail', 0, id], ['/error', -2]) };
		}
	}

	nodeIDs { ^synths ++ (listenSynth !? { |id| [id] } ? []) }

	// as many synths as the list needs, each told its sixteen channels
	prApply {
		var chunks = audio.clump(slots);
		if (wanted != true or: { server.serverRunning.not }) { ^this };
		while { synths.size > chunks.size } {
			server.sendBundle(nil, ['/error', -1], ['/n_free', synths.last], ['/error', -2]);
			synths = synths.drop(-1);
		};
		chunks.do { |chunk, k|
			var args = chunk ++ (-1 ! (slots - chunk.size));
			if (k < synths.size) {
				server.sendMsg('/n_setn', synths[k], \busses, slots, *args);
			} {
				var id = server.nextNodeID;
				synths = synths.add(id);
				server.sendMsg('/s_new', \sp_probe16, id, 1, 0, \replyID, baseID + k);
				server.sendMsg('/n_setn', id, \busses, slots, *args);
			}
		};
		// what is no longer measured is no longer known
		levels.keys.copy.do { |k| if (audio.includes(k).not) { levels.removeAt(k); raws.removeAt(k); bads.removeAt(k) } };
	}

	prReply {
		|msg|
		var k = msg[2] - baseID, vals = msg[3..], base;
		if (k < 0 or: { k >= maxSynths }) { ^this };
		base = k * slots;
		slots.do { |j|
			var index = audio[base + j];
			if (index.notNil) {
				var peak = vals[j], old = levels[index] ? 0;
				raws[index] = peak;
				// held, then let fall: a light that follows it does not strobe
				levels[index] = if (peak >= old) { peak } { max(peak, old * 0.7) };
				bads[index] = vals[slots + j].asInteger;
			}
		};
		// the last synth's reply ends the batch
		if (k == (synths.size - 1)) { action.value(this) };
	}

	// ---------------------------------------------------------------- listening

	// Play an audio bus on the outputs (listenOut), beside whatever else
	// plays there. One at a time: listening to another stops the first.
	listen {
		|bus, channels = 1|
		var id;
		this.stopListening;
		if (alive.not or: { server.serverRunning.not }) { ^this };
		id = server.nextNodeID;
		listenSynth = id;
		listening = [bus, channels];
		// before the first probe when there is one (2: add before), else at the root's tail
		server.sendMsg('/s_new', ("sp_listen" ++ channels.clip(1, 2)).asSymbol, id,
			if (synths.isEmpty) { 1 } { 2 }, if (synths.isEmpty) { 0 } { synths[0] },
			\bus, bus, \out, listenOut, \amp, listenAmp);
	}

	stopListening {
		listenSynth !? { |id|
			if (server.serverRunning) { server.sendBundle(nil, ['/error', -1], ['/n_set', id, \gate, 0], ['/error', -2]) }
		};
		listenSynth = nil;
		listening = nil;
	}

	isListeningTo { |bus| ^listening.notNil and: { listening[0] == bus } }
}
