// =====================================================================
// The server's own windows, opened as ServerPanel's
//
// s.scope, s.freqscope, s.meter and s.plotTree - and the IDE's Server
// menu, which calls exactly these - open SPScope, SPFreqScope, SPMeter
// and SPTree. So do Bus:scope, Function:scope (both go through
// Server:scope) and Function:freqscope.
//
//   s.scope;                         // an SPScope on the outputs
//   s.scope(2, 8);                   // two channels from bus 8
//   { SinOsc.ar([440, 441]) }.scope; // a function's output, as before
//   s.meter; s.freqscope; s.plotTree;
//
// To have the stock windows back, without recompiling:
//
//   ServerPanel.replaceStockWindows = false;
//
// These methods replace the class library's own (server-scope.sc,
// server-meter.sc, ServerPlusGUI.sc). The stock bodies are copied below,
// as they are in SuperCollider 3.13, for when replaceStockWindows is off.
// =====================================================================

+ Server {
	scope { arg numChannels, index = 0, bufsize = 4096, zoom = (1), rate = \audio;
		numChannels = numChannels ?? { if (index == 0) { options.numOutputBusChannels } { 2 } };
		if (ServerPanel.replaceStockWindows) {
			^SPScope(this).prSetProperties(numChannels, index, rate)
		};
		if(scopeWindow.isNil) {
			scopeWindow = Stethoscope(this, numChannels, index, bufsize, zoom, rate, nil,
				this.options.numBuffers);
			scopeWindow.window.onClose = scopeWindow.window.onClose.addFunc({ scopeWindow = nil });
		} {
			scopeWindow.setProperties(numChannels, index, bufsize, zoom, rate);
			scopeWindow.run;
			scopeWindow.window.front;
		};
		^scopeWindow
	}

	freqscope {
		if (ServerPanel.replaceStockWindows) { ^SPFreqScope(this) };
		^FreqScope.new(server: this);
	}

	meter { |numIns, numOuts|
		if (ServerPanel.replaceStockWindows) { ^SPMeter(this) };
		if(numIns.notNil or: numOuts.notNil and: { serverMeter.notNil }) { serverMeter.close };
		if(serverMeter.isNil or: { serverMeter.isClosed }) {
			serverMeter = ServerMeter(this, numIns, numOuts)
		} {
			serverMeter.window.front
		}
	}

	plotTree { |interval = 0.5|
		var onClose, window;
		if (ServerPanel.replaceStockWindows) { ^SPTree(this).interval_(interval) };
		window = Window.new(name.asString + "Node Tree", Rect(128, 64, 400, 400), scroll: true).front;
		window.view.hasHorizontalScroller_(false).background_(Color.grey(0.9));
		onClose = this.plotTreeView(interval, window.view, { defer { window.close } });
		window.onClose = { onClose.value };
	}
}

+ Function {
	freqscope {
		var server = Server.default;
		this.play(server);
		^server.freqscope
	}
}
