// UnitTestScript("ServerPanel", thisProcess.nowExecutingPath).runScript;
{
	|test|
	var win;

	ServerPanel(\ServerViewUnitTest).front;
	0.1.wait;
	test.assert(ServerPanel(\ServerViewUnitTest).window.notNil, "Made a window.");
	test.assert(ServerPanel(\ServerViewUnitTest).window.visible, "Window is visible.");

	win = ServerPanel(\ServerViewUnitTest).window;
	ServerPanel(\ServerViewUnitTest).close;
	0.1.wait;
	test.assert(win.isClosed, "Window not visible after close.");
	test.assert(ServerPanel(\ServerViewUnitTest).window.isNil, "Window is nil'd'.");
	test.assert(ServerPanel(\ServerViewUnitTest).view.isNil, "View is nil'd'.");

}

