# ServerPanel

**A compact server window, with a scope, a frequency scope, level meters and a node tree to match.** A SuperCollider extension.

The window the server opens on boot: CPU and node counters with their history,
a RAM bar (the server's samples, the rest of scsynth, other programs, what is free),
input and output meters around a small scope, a peak history, the volume and a
recorder row, in one dark palette. The `s`, `f`, `l` and `p` keys open a scope,
a frequency scope, level meters and the node tree in the same colours, each
with a line along the bottom that reads out what is under the pointer.

```supercollider
s.boot;              // the panel opens by itself
ServerPanel().front; // or bring it back
SPScope(s);          // the oscilloscope
SPFreqScope(s);      // the spectrum
SPMeter(s);          // every input and output
SPTree(s);           // what is running on the server
```

![the panel](ui-shots/panel.png)

The pictures in this README live in `ui-shots/`, which the repository ignores:
make them on your machine with the windows open, or read them on the author's.

## Where it comes from

ServerPanel is a fork of Scott Carver's
[ServerView](https://github.com/scztt/ServerView.quark), the server window
of his quark. His design is the base of the panel: a column of widgets
(`ServerPanel : Singleton`, a widget per row, a key per action), the CPU and
node counters with their graphs, the scope beside the meters, the peak
history, the volume and record rows. The history of this repository up to the
fork is his, and the license is his MIT license.

The classes have new names (`ServerPanel`, `SPWidgetBase`, `SPScope` ...), so
the two can be installed at the same time without a duplicate class error. Both
open their window when the server boots, though, so keep one.

What the fork adds:

- one dark palette and one way of drawing every window: a row of controls on
  top, a hint bar along the bottom that says what the pointer is over, space to
  run and pause, escape to close (`SPWindow`);
- a RAM bar: the server's samples, the rest of scsynth, other programs, what is free;
- four windows that replace the stock ones, from `s.scope`, `s.freqscope`,
  `s.meter`, `s.plotTree` and the IDE's Server menu: `SPScope`, `SPFreqScope`,
  `SPMeter`, `SPTree`;
- the node tree as a debugger: busses, problems, the path of a signal, a log,
  snapshots, an inspector for any node (`SPTree`, `SPInspector`, `SPRouting`,
  `SPProbe`, `SPLog`, `SPHealth`, `SPWatch`, `SPSnapshot`);
- an options window in the same colours, with changes that wait for apply (`SPOptions`);
- buttons into the other tools of the environment, below.

## How it connects to the other tools

It names none of them in its code: each is looked up by name when the panel is
made, and a tool that is not installed is a button that is not there.

| tool | what the panel does with it |
|---|---|
| [FxKit](https://github.com/fracnesco/sc-fx-kit) (`FxChain`) | the `fx` row: a chain of effects on the main out, with its own window. See [the master fx](#the-master-fx) |
| [Compressor](https://github.com/fracnesco/sc-compressor) | put in that chain after the eq |
| [ParaEQ](https://github.com/fracnesco/sc-eq) | put in that chain first |
| the effects built on FxKit | the chain's **+ effect** menu |
| [Insight](https://github.com/fracnesco/supercollider-insight-meter) | the `i` button by the meters: spectrum, loudness and image of the main out |
| [BusRecorder](https://github.com/fracnesco/sc-recorder) | the `r` button in the record row |
| [BusManager](https://github.com/fracnesco/sc-bus-manager) | the node tree names busses by their BusManager names |
| [SuperDirtGUI](https://github.com/fracnesco/superdirt-gui) | the node tree names SuperDirt's orbits, and SuperDirtGUI teaches it its faders, inserts, tracks and returns |
| JITLib | an `Ndef`'s group is labelled with its name, and its synth is inspected from its proxy |

The order on the main out is what makes the meters right: modules and Ndefs in
the default group, then the volume, then the master fx chain, then, at the tail
of the root node, the panel's meters, the peak history, Insight and the
recorder. Everything the panel measures is what leaves the computer.

## What it depends on

The quarks ServerView needs, but NodeSnapshot: Singleton, WindowViewRecall,
WindowHandleView, ServerOptionsGui, Connection and Preference. Installed as a
quark, they come with it.

The tools in [How it connects](#how-it-connects-to-the-other-tools) are
optional. Each one adds a button when it is installed and nothing when it is not.

Written for SuperCollider 3.13 on macOS.

## Install

### As a quark

```supercollider
Quarks.install("https://github.com/fracnesco/sc-server-panel.git");
thisProcess.recompile;
```

If you had ServerView, take it off first, or both windows open on boot:

```supercollider
Quarks.uninstall("ServerView");
```

### From a clone

Clone the repository, open `install.scd` and evaluate the first block. It links
the folder into the extensions folder as `ServerPanel`; then recompile
(ctrl/cmd + shift + L). `ln -s` does not exist on Windows: use the quark there.

One or the other, not both: two copies on the class path is a duplicate class
error at compile time.

### Check that it took

```supercollider
[\ServerPanel, \SPScope, \SPFreqScope, \SPMeter, \SPTree].every { |c| c.asClass.notNil };   // -> true
```

## The panel

| key | what it does |
|---|---|
| `b` | boot the server |
| `s` | the scope (`SPScope`) |
| `f` | the frequency scope (`SPFreqScope`) |
| `p` | the node tree (`SPTree`) |
| `n` | the nodes in the post window |
| `l` | the level meters (`SPMeter`) |
| `o` | the server's options (`SPOptions`) |
| `i`, `r`, `x` | Insight, the recorder, the master fx (when installed) |
| `d` | dump OSC |
| `m`, `0` | mute, volume back to 0 dB |

The same entries are in the options menu at the top right.

## The master fx

The `fx` button opens a chain of effects on the main out (`FxChain.master`,
from [FxKit](https://github.com/fracnesco/sc-fx-kit)): an eq if
[ParaEQ](https://github.com/fracnesco/sc-eq) is installed, and a compressor if
[Compressor](https://github.com/fracnesco/sc-compressor) is installed, both off
until you switch them on. The line beside the button says what is in the chain
and what is on, whether its window is open or not. The chain runs after the
volume and before the meters, so everything the panel shows is post-chain.

The chain is not limited to those two. Its window loads the effect modules of
the environment: **+ eq** and **+ compressor**, and **+ effect**, a menu of every
effect built on [FxKit](https://github.com/fracnesco/sc-fx-kit) that is
installed. Each is a repository of its own; install the ones you want and
recompile, and they are in the menu:

| module | what it is |
|---|---|
| [Growl](https://github.com/fracnesco/sc-growl) | three stages of saturation, routed, fed back and modulated |
| [Tunnel](https://github.com/fracnesco/sc-tunnel) | a stereo delay with a character section |
| [Smear](https://github.com/fracnesco/sc-smear) | a spectral processor: freeze, blur, scramble, shift, comb |
| [Cloud](https://github.com/fracnesco/sc-cloud) | a live granulator |
| [Stammer](https://github.com/fracnesco/sc-stammer) | a beat repeat |
| [Sympathy](https://github.com/fracnesco/sc-sympathy) | six tuned voices that ring when the sound touches them |
| [Spiral](https://github.com/fracnesco/sc-spiral) | a frequency shifter in a feedback loop |
| [Reverbs](https://github.com/fracnesco/sc-reverbs) | Hall, Plate, Coil (a spring) and Shimmer |
| [Imager](https://github.com/fracnesco/sc-imager) | a stereo imager in up to four bands |

Every effect has its presets (the menu by its name), and the chain saves and
loads all of it as one preset (**save**, **load**, the menu at the top). Here,
with a beat playing, the eq on its `warm` preset and the compressor on `glue`:

![the master fx: the eq](ui-shots/fx-eq.png)

![the master fx: the compressor](ui-shots/fx-compressor.png)

```supercollider
c = FxChain.master(s);
c.eq.preset = \warm; c.eq.enabled = true;
c.compressor.preset = \glue; c.compressor.enabled = true;
c.gui;
```

## The windows

They replace the stock ones everywhere: `s.scope`, `s.freqscope`, `s.meter`
and `s.plotTree` open them, and so does the IDE's Server menu, which calls
exactly those, and `Bus:scope`, `{ ... }.scope` and `{ ... }.freqscope`. To have
the stock windows back, without recompiling:

```supercollider
ServerPanel.replaceStockWindows = false;
```

The methods are replaced from `classes/SystemOverwrites`, the folder
SuperCollider expects intentional overwrites in, so the class library compiles
without warnings about them.

Each is one window per server: asking again brings the open one to the
front. They survive cmd + period and a reboot, and free what they hold on the
server when you close them. Space runs and pauses, escape closes.

![SPScope](ui-shots/scope.png)

**SPScope** shows any bus, audio or control, as a lane per channel, overlaid or
x y. The grid marks 0.5 and 1 in each lane (they move with the zoom) and the
time the screen holds. The mouse wheel zooms the level; sideways, or with shift,
it zooms the time.

![SPFreqScope](ui-shots/freqscope.png)

**SPFreqScope** shows the spectrum of a stereo bus, left and right summed or
one of them, on a logarithmic or linear scale, with the fft size (2048, 4096,
8192) and the range (48 to 144 dB) in the top row. The level is the same at
every fft size: a sine at -6 dBFS reads about -6.5 dB. Pointing at the spectrum
reads the frequency, the nearest note with its cents, and the level.

The one thing these windows have to get right is that the trace and the grid
agree. The scope view under the grid never spreads one point over several
pixels, so the frequency scope always draws a sweep of 2048 points, which fills
any window up to 2048 pixels wide. With the stock 512 points, a 5 kHz tone in a
600-pixel window showed at 4 kHz.

Both scopes read the server's scope buffers through shared memory, as the stock
ones do, so they need a server on this machine.

![SPMeter](ui-shots/meter.png)

**SPMeter** has a bar per input and output: the rms as the body, in blue, then
orange above -6 dB and red above -1, over a dim column that reaches the peak; the
peak as a white line and the held peak as an orange one. A light over each bar
goes red at 0 dBFS and stays red; the number under it is the highest peak since
the last reset. Click the meters (or **reset**) to let go of all of it. The range
goes down to -48, -60, -80 or -96 dB, and a peak is held 1 s, 3 s or until a
click. Its synth sits at the tail of the root node, so it reads the outputs
after everything.

The scale is a mixing desk's (IEC 60268-18): the top 20 dB, where levels are
set, take half the height. The rms is averaged over 300 ms, as a VU meter is,
so the body moves the way the loudness is heard and the peak line shows the
transients; the averaging is done in the language, and the server runs one
`SendPeakRMS` for all the channels. The bars share the window's width and stay
in the middle once they are at their widest.

![SPTree with the inspector](ui-shots/tree.png)

**SPTree** shows the node tree as an outline, one row per node, indented by
depth. A group says what it is when that can be known: the root, the default
group, an `Ndef`, SuperDirt and its orbits, an fx chain. A synth shows its
SynthDef in a colour of its own (one def, one colour), what it is at the right,
a light for what comes out of it and a red dot when something looks wrong.

It is also the place to debug from. The menu at its top left changes what the
list shows:

| view | what it answers |
|---|---|
| **tree** | what is running, in the order the server runs it |
| **busses** | who writes and who reads each bus, and how loud it is |
| **problems** | a synth that reads a bus before it is written, synths that pile up, a paused synth left behind, a NaN on a bus, a FAILURE |
| **signal path** | where a synth's sound goes, bus by bus, to the outputs - and where it stops |
| **log** | every node that started or ended, with the time |
| **ended** | the synths that have ended, still inspectable |
| **buffers** | the buffers, and which synths read them |
| **changes** | what is different since a snapshot |

![SPTree: the busses](ui-shots/tree-busses.png)

![SPTree: the path of a signal](ui-shots/tree-path.png)

It stays readable while a pattern plays (**calm**, on by default): rows move at
most every 0.7 s, what you are looking at stays where it is, and a group that
makes many short-lived nodes shows them as one line. The strip under the top row
is the last minute and a half: hold the window still (space) and click it, or
press left and right, to step back. **snapshots** keeps a moment, compares two,
writes one to disk. The hint bar along the bottom says what the pointer is
over and, over nothing, how to use the view you are in; **hints** folds it away.
[docs/node-tree-tutorial.md](docs/node-tree-tutorial.md) walks through it,
[docs/node-tree.md](docs/node-tree.md) is the reference.

```supercollider
SPTree(s).mode = \busses;
SPTree(s).showPath(1001);                       // where that synth's sound goes
SPTree(s).snapshot;                             // keep this moment
SPTree(s).compare(SPTree(s).snapshots.first);   // what changed since
```

### The inspector

Click a node and the panel at the right of the tree says everything that can be
known about it, to find out which synth is what and why it sounds the way it
does:

- what it is and who made it, and the groups above it by name;
- whether it runs or is paused (a SuperDirt effect asleep is paused), and for how long it has been seen;
- its controls with their values and defaults; type a value and return to set it (`/n_set`); a control mapped to a bus says which;
- the busses it reads and writes, with names (BusManager's, SuperDirt's orbits, the hardware), from its SynthDef's `In` and `Out` UGens and the controls that feed them;
- the UGens inside it;
- what looks wrong with it, in red, and the buffers its controls name;
- buttons: **trace** (`/n_trace`, every UGen's inputs and outputs once, into the post window), **pause** / **run**, **free** (click twice), **path** (where its sound goes), **scope out** and **freq** (the bus it writes, in `SPScope` and `SPFreqScope`), **listen** (that bus on the outputs), **source** (the file it came from, when that is known), **copy** (a line of code that reaches it), **post** (all of this as text);
- **w** beside a control keeps its value in view under the tree.

A bus (from the busses view or a path) shows its level, its writers and readers
in the order they run, and the same buttons; a buffer its file and its readers.

**ended** lists the synths that have ended, newest first, and the inspector
still shows what was seen of them. A SuperDirt event's synths live a fraction of
a second, shorter than the tree asks the server, so the tree asks for every new
group's contents the moment it appears (`/n_go`): an event's synths are caught
there even when they never show in the outline. Up and down move the selection.

What the SynthDef says - defaults, busses, UGens - is known when this sclang has
its `SynthDesc`, which every def made here with `.add` has. Whether a node is
paused is known from the server's notifications, so only since the window
opened. The server has no CPU figure per synth, and a synth's own level only
shows when it writes to a bus of its own (**scope out** shows the whole bus).

```supercollider
SPTree(s).inspect(1001);                        // the window on a node
SPInspector.textOf(SPTree(s).recordOf(1001), s).postln;
```

### Your own names

Teach it what your groups, synths and busses are. A describer answers an Event:
`name`, `where`, `text`, `file`, `actions` (buttons in the inspector); for each key
the first answer wins. SuperDirtGUI adds its own this way, so its faders,
inserts, tracks and returns, and the content file of an event's synth, are named.

```supercollider
SPTree.labellers.add({ |server, labels| labels[~myGroup.nodeID] = "my group" });
SPTree.notes.addFirst(["^my_", "one of mine"]);
SPTree.describers.addFirst({ |server, node|
	if (node[\def] == \myPad) { (name: "the pad", file: "~/pads.scd".standardizePath,
		actions: [["louder", { |n, srv| srv.sendMsg(\n_set, n[\id], \amp, 0.3) }]]) }
});
SPTree.busNamers.addFirst({ |server, rate, index| if (rate == \audio and: { index == 20 }) { "my send" } });
```

### The options

![SPOptions](ui-shots/options.png)

**SPOptions** (`o`) is the server's options in the same colours, in cards: the
audio device, the channels, what the server holds (the real-time memory in MB,
not in kB), recording and timing; the rare settings fold under **more**. A
setting you change is lit and waits: **apply** sets the changes, **apply and
reboot** reboots too when a change needs it, **revert** forgets them. The top
row says how many are waiting and whether they need a reboot; the hint bar says
what each setting does. Latency and the recording settings take effect at once.

```supercollider
SPOptions(s);
SPOptions(s).set(\memSize, 2 ** 17);
SPOptions(s).applyAndReboot;
```

## Files

| file | what it is |
|---|---|
| [classes/ServerPanel.sc](classes/ServerPanel.sc) | the panel and its widgets: `ServerPanel`, `SPWidgetBase` (colours, the optional extensions), `SPStatusWidget`, `SPMasterFxWidget`, `SPScopeWidget`, `SPHistoryWidget`, `SPVolumeWidget`, `SPRecordWidget` |
| [classes/SPWindow.sc](classes/SPWindow.sc) | `SPWindow`, what the windows share: one per server, the top row, the hint bar and its **hints** box, run and pause, the server hooks |
| [classes/SPScopes.sc](classes/SPScopes.sc) | `SPScopeWindow`, and the two scopes on it: `SPScope`, `SPFreqScope` |
| [classes/SPMeter.sc](classes/SPMeter.sc) | `SPMeter`, the level meters |
| [classes/SPOptions.sc](classes/SPOptions.sc) | `SPOptions`, the server's options |
| [classes/SPTree.sc](classes/SPTree.sc) | `SPTree`, the node tree and its views, and what it knows: labellers, describers, bus names, checkers, history |
| [classes/SPRouting.sc](classes/SPRouting.sc) | `SPRouting`, the tree as busses: who writes and reads what, the order problems, the path of a signal |
| [classes/SPProbe.sc](classes/SPProbe.sc) | `SPProbe`, the level on any set of busses, and a way to listen to one |
| [classes/SPTreeTools.sc](classes/SPTreeTools.sc) | `SPLog`, `SPHealth`, `SPWatch`, `SPSnapshot`: the log, what only shows over time, watched values, the tree at one moment |
| [classes/SPInspector.sc](classes/SPInspector.sc) | `SPInspector`, the panel at the right of the tree: everything about one node |
| [classes/SystemOverwrites/extServer.sc](classes/SystemOverwrites/extServer.sc) | `s.scope`, `s.freqscope`, `s.meter`, `s.plotTree` opening the four windows, with the stock bodies for `replaceStockWindows = false` |
| [HelpSource/Classes](HelpSource/Classes) | help for `ServerPanel`, `SPScope`, `SPFreqScope`, `SPMeter`, `SPOptions`, `SPTree`, `SPInspector`, `SPRouting`, `SPProbe`, `SPSnapshot`, `SPLog`, `SPHealth`, `SPWatch` |
| [docs/node-tree-tutorial.md](docs/node-tree-tutorial.md) | the node tree from the first click: read a row, inspect, find why something is silent, go back in time |
| [docs/node-tree.md](docs/node-tree.md) | debugging with the node tree: every view, the rules behind the problems, how it stays readable, what it can not know |
| [Test/ServerPanel_unittest.scd](Test/ServerPanel_unittest.scd) | ServerView's unit test, renamed |
| [install.scd](install.scd) | links a clone into the extensions folder, checks, unlinks |

## Issues

https://github.com/fracnesco/sc-server-panel/issues

## License

MIT, as ServerView is: Copyright (c) 2013 Frank Scott Carver, (c) 2026
Francesco Corvi. See [LICENSE](LICENSE).
