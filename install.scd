// Symlink this repository into the SuperCollider extensions folder.
// Evaluate the block below, then recompile the class library
// (Language -> Recompile Class Library, or ctrl/cmd + shift + L).
//
// This is the route for a clone you want to keep editing. If you would
// rather have it installed and updated for you, do not use this file -
// install it as a quark instead, from a fresh sclang:
//
//   Quarks.install("https://github.com/fracnesco/sc-server-panel.git");
//   thisProcess.recompile;
//
// One or the other, not both: two copies on the class path is a
// duplicate class error at compile time.
//
// ln -s is macOS and Linux. On Windows, use the quark, or copy this
// folder into Platform.userExtensionDir by hand - .openOS opens it.
//
// Needs the quarks scztt's ServerView needs, NodeSnapshot apart: Singleton,
// WindowViewRecall, WindowHandleView, ServerOptionsGui, Connection and
// Preference - the quark route installs them; with a link, install them
// first (Quarks.install("Singleton") and so on). Insight, BusRecorder and
// ParaEQ (FxChain) are optional: each adds a button when it is installed.
//
// It replaces ServerView: both can be installed, but both take over the
// window the server opens on boot - uninstall ServerView to keep this one.

(
var src = PathName(thisProcess.nowExecutingPath).pathOnly;
var dst = Platform.userExtensionDir +/+ "ServerPanel";

// drop the trailing slash, ln -s does not like it
if (src.endsWith("/")) { src = src.drop(-1) };

case
{ File.exists(dst) } {
	("ServerPanel: % already exists - remove it first if you want to relink."
		.format(dst)).warn;
}
{ true } {
	File.mkdir(Platform.userExtensionDir);
	("ln -s" + src.shellQuote + dst.shellQuote).unixCmd({ |exit|
		if (exit == 0) {
			"ServerPanel: linked".postln;
			("   " ++ src).postln;
			("-> " ++ dst).postln;
			"".postln;
			"Now recompile the class library: ctrl/cmd + shift + L".postln;
		} {
			("ServerPanel: linking failed with exit code" + exit).warn;
		};
	});
};
)


// ---------------------------------------------------------------- verify
(
var missing = [\ServerPanel, \SPWidgetBase, \SPScope, \SPFreqScope, \SPMeter, \SPTree]
	.select { |sym| sym.asClass.isNil };

if (missing.notEmpty) {
	("ServerPanel is not compiled - % is missing."
		"\n  Recompile the class library, ctrl/cmd + shift + L, and check the"
		"\n  post window for compile errors.".format(missing.join(", "))).warn;
} {
	"ServerPanel is installed.".postln;
	"  ServerPanel().front;  SPScope(s);  SPFreqScope(s);  SPMeter(s);  SPTree(s);\n  help: ServerPanel, then cmd+d".postln;
};
)


// ------------------------------------------------------------- uninstall
(
var link = Platform.userExtensionDir +/+ "ServerPanel";
case
{ File.type(link) == \symlink } {
	File.delete(link);
	("Removed %."
		"\n  Recompile the class library to finish.".format(link)).postln;
}
{ File.type(link) == \not_found } {
	"Nothing to remove at %.".format(link).postln;
}
{ true } {
	"% is not a link, leaving it alone.".format(link).warn;
};
)
