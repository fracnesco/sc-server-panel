# Debugging with the node tree

`SPTree(s)` (the `p` key of ServerPanel, or `s.plotTree`) is one window with
eight views of the same thing: what runs on the server, in what order, on which
busses. This page says what each view answers, how the window stays readable
while a pattern plays, and what it can not know.

```supercollider
SPTree(s);
SPTree(s).mode = \busses;          // or pick it in the menu at the top left
```

## The views

| view | the question it answers |
|---|---|
| **tree** | What is running, and in what order? One row a node, in the order the server runs them. |
| **busses** | Who is on this bus? Every bus in use: who writes it, who reads it, how loud it is. |
| **problems** | What looks wrong? The list behind the red **problems** button. |
| **signal path** | Why is it silent? Where a synth's sound goes, bus by bus, and where it stops. |
| **log** | What just happened? Every node that started or ended, with the time. |
| **ended** | What was that? The synths that have ended, newest first, still inspectable. |
| **buffers** | Which sample is that? The buffers, and the synths that read them. |
| **changes** | What is different? The tree now against a snapshot, or two snapshots. |

Click any row and the inspector at the right shows the node, bus or buffer it
is about. A double click goes further: in the tree it traces a synth
(`/n_trace`), on a bus or a path step it follows the signal from there, on a
problem or a log line it goes to the node in the tree.

### tree

A synth row has, from the left: a **light** (green when the busses it writes
carry signal, brighter when louder, grey when silent, red for a NaN or an inf),
a dot in its SynthDef's colour, the def, its id, and at the right what it is. A
**red dot** at the right says something looks wrong with it; the inspector says
what. A group row says how many synths it holds.

### busses

One row per bus, a stereo pair as one row, under four headings: hardware
outputs, hardware inputs, private audio busses, control busses. Each has its
name when something knows it (BusManager, SuperDirt's orbits, SuperDirtGUI's
tracks and returns), the SynthDefs that write it, an arrow, those that read it,
and its level. Click one: the inspector lists writers and readers in the order
they run, each a button to that node, with **path**, **scope**, **freq**,
**listen** and **watch**.

### problems

What is listed, and why it matters:

| problem | what it means |
|---|---|
| a synth **reads a bus before it is written** | `In.ar` hears a bus only if a synth earlier in the tree wrote it in the same block. A reader placed before its source hears silence. The classic "it makes no sound and nothing is wrong". Fix: move it after, or read with `InFeedback`. |
| ... **misses what X writes** (a note) | The same, but something else did write the bus before it, so it hears part. |
| synths of a def **keep piling up** | A def's count has only grown for at least five samples (25 s). Something starts them and nothing frees them. |
| a synth **outlives its kind** | The others of its SynthDef end after a moment; this one has been there twenty times as long. Probably never released. |
| a synth **paused beside a running twin** | Same def, same group, same busses, one paused for more than 20 s. SuperDirt's sleeping effects leak this way: released while asleep, never freed. |
| a bus **carries a NaN** (or an inf, or denormals) | Read by the probes. Everything after it is poisoned. |
| the server said **FAILURE** | Listed for half a minute, with the node it names. |
| a bus **nothing reads**, or **nothing writes** (notes) | Often by design (a tap for a recorder); listed under notes. |

A reader is only called early when a *new source* of its bus runs after it. A
mixer's chain on one bus (a send that reads it, the effect, the return that
writes the effect back, a fader that works on it in place) is downstream of the
bus by design, and raises nothing: SuperDirt with SuperDirtGUI on it, playing or
at rest, lists no warning (checked 2026-11-14). And a bus that anything ever
wrote before a reader (an orbit's bus, written by events that are not always
there) makes the problem a note, not a warning.

Your own system can add what only it knows to look for:

```supercollider
SPTree.checkers.add({ |server, model|
	if (~myFx.notNil and: { model[\pos][~myFx.nodeID] < model[\pos][~mySource.nodeID] }) {
		[(level: \warn, id: ~myFx.nodeID, key: "myfx-order", text: "my fx runs before its source")]
	}
});
```

### signal path

Select a synth and press **path** in the inspector (or `SPTree(s).showPath(id)`).
At the top, the answer in words: *it reaches audio 0-1 (hardware out 0), with
signal all the way*, or where it stops:

- *nothing is coming out of X right now* - the synth itself is silent;
- *the sound stops in X: bus A going in has signal, bus B coming out is silent* - a gain at 0, a gate closed, a filter shut;
- *X is paused: nothing gets past it*;
- *it ends at bus B: nothing reads that bus after it is written* - with, under it, the synths that read it too early.

Then what feeds it, and the way out step by step: each synth, each bus with its
level. A way that ends in a meter or a tap, while another way gets out, is a grey
remark, not an answer.

### log and ended

The log has a line for every node that started, ended, was paused or resumed,
every FAILURE, every problem that came and went, every snapshot. With **calm** on,
a burst (many synths of one def in one group, less than a second apart) is one
line with a count: `started  dirt_sample_1_2 ×57  in orbit 0  ·  over 3.4 s`.
**snapshots → write the log to disk** saves it as text.

A synth that lives a fraction of a second is caught two ways: a new *group*'s
contents are asked for the moment it appears (SuperDirt's events are groups),
and when the server says a *synth* started, its group is asked for (at most
twenty times a second a group). Its true starting time is the server's.

### buffers

The buffers this sclang made, with file, length and channels (and SuperDirt's
name, `bd:3`), those a synth reads right now first. A synth "reads" a buffer
when one of its controls whose name says `buf` holds its number.

### snapshots and changes

**snapshots → take a snapshot now** keeps the whole tree: every node with its
controls, every bus with its level, the problems. From the same menu: look at
one again (the window holds still on it), see **what changed since** one (against
the tree as it is now, live), compare the last two, write one to disk
(`SPSnapshot.folder`, an archive to read back and a `.txt` to read or send).

The changes are: nodes added and removed (several synths of one def in one group
as one line), nodes that moved (another group, or out of step with their
neighbours), controls that changed (`freq 300 → 777`), busses whose writers or
readers are other SynthDefs or that went from sounding to silent, problems that
came or went.

```supercollider
t = SPTree(s);
t.snapshot;                          // made from the server's next answer
// ... change something ...
t.compare(t.snapshots.first);        // against now
SPSnapshot.text(t.snapshots.first).postln;
t.saveSnapshot;
```

## The strip, and stepping back

Under the top row: the last minute and a half, newest at the right. Blue bars up
are nodes that started in each half second, grey bars down those that ended;
an orange tick is a problem, a red one a FAILURE, a white line a snapshot.

Every answer of the server is kept for those 90 seconds. Hold the window still
(space, or **run**) and click the strip: the window shows that moment - tree,
busses, levels, problems. Left and right step one answer at a time. **run** goes
back to now.

## Watching, listening

- **w** beside a control in the inspector, or **watch** on a bus: the value stays
  in view under the list, with its last moments as a line.
- **listen** on a synth or a bus: its bus is played on the outputs
  (`SPProbe.listenOut`, 0 by default), beside what already plays there. Again to stop.
- **scope**, **freq**: the bus in `SPScope` and `SPFreqScope`.

## Staying readable while things change fast

A pattern makes and frees dozens of nodes a second. Shown as they come, every
row would jump. With **calm** on (the default):

- The list is laid out - rows added, removed, moved - at most once every
  `SPTree.calmTime` seconds (0.7). In between, what the rows show is drawn in
  place: levels, lights.
- What is at the top of the view stays there. Rows that come and go above it do
  not push it around; in the log, scrolled down, new lines arrive above without
  moving what you read.
- A group that makes two or more nodes a second (`SPTree.busyRate`) shows its
  short-lived children as **one line**: *short-lived nodes: 3 here now, about 16
  a second · dirt_gate2, dirt_sample_1_2*. Click it to have them one by one. Each
  of them is still in the log and under ended.
- A node that ended between two layouts keeps its row, dimmed, until the next.
- Numbers hold still long enough to be read: a level's text is written again
  twice a second at most, an ended synth shows the time it ended (not a ticking
  "ago"), a group's count leaves the short-lived ones out and says so.
- A bus only events write keeps its row for five seconds after it was last used.

Measured 2026-11-14 with 30 synths a second coming and going, five seconds in
each view: 7 layouts, the row at the top of a scrolled view never changed, no
row that stayed on screen changed place in the tree, busses, problems, ended
and path views, and the view was drawn 11 times a second. The same with
SuperDirt and SuperDirtGUI playing 16 events a second on two orbits: 78 rows
before, 80 during.

**calm** off shows everything as it comes.

## What it measures with

Levels come from probes: synths at the tail of the root node that read up to
256 bus channels (sixteen each, so a stock server's wire buffers are enough) and
answer ten times a second with each channel's peak and whether it carried a bad
value. 16 probes cost about 2 % of a core on the server (measured with 411
synths running). The probes and the listener are left out of the tree: they are
the window's own.

## What it can not know

- A synth whose SynthDef this sclang has no `SynthDesc` of (made on another
  client, or sent with `.send`/`.play` and not `.add`): its busses are unknown.
  The busses view says how many there are.
- A bus worked out inside a graph, or a bus a control is *mapped* to.
- A level is a bus's, not a synth's: a synth's light is the busses it writes, so
  two synths on one bus share it.
- Whether a synth was paused before the window opened: the server only says
  when it happens.
- CPU per synth: the server has one figure for all.
- What `/n_trace` prints: it goes to the post window, not back to the client.
- A tree with its controls too big for one answer of the server (about 400
  synths of 60 controls): the window then asks for the tree without controls,
  and the controls forty synths at a time; values are a few seconds behind.
