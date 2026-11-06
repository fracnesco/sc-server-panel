# ServerPanel

**A compact server window, with a scope, a frequency scope, level meters and a node tree to match.** A SuperCollider extension.

The window the server opens on boot: CPU and node counters with their history,
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

It is a fork of Scott Carver's
[ServerView](https://github.com/scztt/ServerView.quark). The classes have new
names (`ServerPanel`, `SPWidgetBase`, `SPScope` ...), so the two can be
installed at the same time without a duplicate class error. Both open their
window when the server boots, though, so keep one.

## What it depends on

The quarks ServerView needs, but NodeSnapshot: Singleton, WindowViewRecall,
WindowHandleView, ServerOptionsGui, Connection and Preference. Installed as a
quark, they come with it.

Three extensions are optional. Each one adds a button when it is installed and
nothing when it is not:

| extension | what it adds |
|---|---|
| [Insight](https://github.com/fracnesco/supercollider-insight-meter) | an `i` button by the meters: spectrum, loudness and image of the main out |
| [BusRecorder](https://github.com/fracnesco/sc-recorder) | an `r` button in the record row |
| [ParaEQ](https://github.com/fracnesco/sc-eq) (`FxChain`) | an `fx` row under the counters: an eq, and a compressor if [Compressor](https://github.com/fracnesco/sc-compressor) is installed, on the main out |

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
| `i`, `r`, `x` | Insight, the recorder, the master fx (when installed) |
| `d` | dump OSC |
| `m`, `0` | mute, volume back to 0 dB |

The same entries are in the options menu at the top right.

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

**SPScope** shows any bus, audio or control, as a lane per channel, overlaid or
x y. The grid marks 0.5 and 1 in each lane (they move with the zoom) and the
time the screen holds. The mouse wheel zooms the level; sideways, or with shift,
it zooms the time.

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

**SPMeter** has a bar per input and output: the rms as the body, in blue, then
orange above -6 dB and red above -1, the peak as a white line and the held peak
as an orange one. A light over each bar goes red at 0 dBFS and stays red; the
number under it is the highest peak since the last reset. Click the meters (or
**reset**) to let go of all of it. The range goes down to -48, -60, -80 or -96
dB, and a peak is held 1 s, 3 s or until a click. Its synth sits at the tail of
the root node, so it reads the outputs after everything.

**SPTree** shows the node tree as an outline, one row per node, indented by
depth. A group says what it is when that can be known: the root, the default
group, an `Ndef`, SuperDirt and its orbits, an fx chain. It says how many
synths are inside, and a click folds it away. A synth shows its SynthDef in a
colour of its own (one def, one colour), what it belongs to when its name says
so (a scope, SuperDirt's meter, a SuperDirtGUI insert ...), and with
**controls** on, its controls. Nodes that have just appeared are lit for a
second, so you can see what an event made. The filter keeps the synths whose
def contains what you type; a double click traces a synth into the post window.
Teach it your own names:

```supercollider
SPTree.labellers.add({ |server, labels| labels[~myGroup.nodeID] = "my group" });
SPTree.notes.addFirst(["^my_", "one of mine"]);
```

## Files

| file | what it is |
|---|---|
| [classes/ServerPanel.sc](classes/ServerPanel.sc) | the panel and its widgets: `ServerPanel`, `SPWidgetBase` (colours, the optional extensions), `SPStatusWidget`, `SPMasterFxWidget`, `SPScopeWidget`, `SPHistoryWidget`, `SPVolumeWidget`, `SPRecordWidget` |
| [classes/SPWindow.sc](classes/SPWindow.sc) | `SPWindow`, what the four windows share: one per server, the top row, run and pause, the server hooks |
| [classes/SPScopes.sc](classes/SPScopes.sc) | `SPScopeWindow`, and the two scopes on it: `SPScope`, `SPFreqScope` |
| [classes/SPMeter.sc](classes/SPMeter.sc) | `SPMeter`, the level meters |
| [classes/SPTree.sc](classes/SPTree.sc) | `SPTree`, the node tree |
| [classes/SystemOverwrites/extServer.sc](classes/SystemOverwrites/extServer.sc) | `s.scope`, `s.freqscope`, `s.meter`, `s.plotTree` opening the four windows, with the stock bodies for `replaceStockWindows = false` |
| [HelpSource/Classes](HelpSource/Classes) | help for `ServerPanel`, `SPScope`, `SPFreqScope`, `SPMeter`, `SPTree` |
| [Test/ServerPanel_unittest.scd](Test/ServerPanel_unittest.scd) | ServerView's unit test, renamed |
| [install.scd](install.scd) | links a clone into the extensions folder, checks, unlinks |

## Issues

https://github.com/fracnesco/sc-server-panel/issues

## License

MIT, as ServerView is: Copyright (c) 2013 Frank Scott Carver, (c) 2026
Francesco Corvi. See [LICENSE](LICENSE).
