# The node tree: a tutorial

The node tree shows what runs on the SuperCollider server and helps you find
out why something sounds wrong, or does not sound at all. This page walks
through it from the first click. [node-tree.md](node-tree.md) is the reference:
every rule and every limit.

## 1. Open it

Boot the server, then any of these:

```supercollider
SPTree(s);        // from code
s.plotTree;       // the same window
```

or press `p` in the ServerPanel window, or choose **Show Node Tree** in the IDE's
Server menu.

The window has four parts:

```
 run  [view menu]  every  controls  [filter]  open all  calm  problems  snapshots  inspector   hints
 ┌───────────────────────────────────────────────┐ ┌───────────────┐
 │ the strip: the last 90 seconds                │ │               │
 ├───────────────────────────────────────────────┤ │ the inspector │
 │ the list: one row per node                    │ │ what you      │
 │                                               │ │ clicked       │
 └───────────────────────────────────────────────┘ └───────────────┘
 the hint bar: what the pointer is over, or what you can do here
```

**Read the hint bar.** It is the line along the bottom. Point at anything - a
button, a row, the strip - and it says what that is and what a click does.
Point at nothing and it says how to use the view you are in. The **hints** box
at the top right folds it away once you know the window.

## 2. Read a row

The list is the server's tree, in the order the server runs it: top first.

- A **group** row has a triangle (click it to fold the group), a name when the
  group is known (`default group`, `orbit 0`, `Ndef(\pad)`), and how many synths
  it holds.
- A **synth** row has, from the left:
  - a **light**: green when sound comes out of it, grey when it is silent, red
    for a broken value (NaN);
  - a dot in the colour of its SynthDef (the same SynthDef is always the same colour);
  - the SynthDef's name and the node's id;
  - at the right, what it is (`orbit 0's fader`, `a scope`), and a **red dot**
    when something looks wrong with it.

Type in the **filter** box to keep only the rows that contain a word.
**controls** adds each synth's control values to its row.

## 3. Inspect a node

Click a row. The inspector at the right shows:

- what the node is and where it sits;
- whether it runs or is paused;
- its **controls**: type a number and press return to change one;
- the **busses** it reads and writes, with their level;
- what looks wrong with it, in red;
- buttons:

| button | what it does |
|---|---|
| **trace** | prints every UGen's input and output once, in the post window |
| **pause** / **run** | stops and restarts the synth |
| **free** | frees it (click twice) |
| **path** | shows where its sound goes (step 4) |
| **scope out**, **freq** | opens the bus it writes in the scope or the frequency scope |
| **listen** | plays the bus it writes on your outputs; click again to stop |
| **copy**, **post** | a line of code that reaches the node; everything as text |
| **w** (beside a control) | keeps that value in view under the list |

Up and down arrows move the selection.

## 4. "Why is it silent?"

1. Click the synth that should make sound.
2. Press **path** in the inspector.

The first line is the answer:

| it says | what to do |
|---|---|
| *it reaches hardware out 0, with signal all the way* | the sound gets out: look at your speakers, the volume, the master effects |
| *nothing is coming out of X right now* | the synth itself is silent: check its controls (amp, gate) |
| *the sound stops in X: bus A going in has signal, bus B coming out is silent* | X swallows it: a gain at 0, a closed filter |
| *X is paused: nothing gets past it* | select X and press **run** |
| *it ends at bus B: nothing reads that bus after it is written* | nothing listens to that bus, or the reader runs too early (step 5) |

Under the answer, the way out step by step: each synth, each bus with a level
bar. Double click a step to follow the path from there.

## 5. The red problems button

The **problems** button turns red, with a count, when something looks wrong.
Click it for the list; click a problem to inspect the node it is about.

| problem | what it means |
|---|---|
| reads a bus **before it is written** | the synth sits above its source in the tree, so it hears silence. Move it after the source (`node.moveAfter(source)`), or read with `InFeedback` |
| **keeps piling up** | synths of one SynthDef are started and never freed |
| **outlives its kind** | one synth is still there long after the others like it ended |
| **paused** beside a running twin | a leftover: it holds memory until cmd + period |
| carries a **NaN** | a broken value on a bus: everything after it is poisoned |
| **FAILURE** | the server refused a command |

The grey **notes** under them (a bus nothing reads) are often by design.

## 6. Who is on this bus?

Choose **busses** in the view menu. One row per bus: its number and name, who
writes it → who reads it, and its level. Click a bus: the inspector lists the
writers and readers in the order they run, each a button that jumps to that
node. Double click a bus to follow where its signal goes.

## 7. What just happened?

- **log** (view menu): every node that started or ended, with the time. A
  pattern's events are one line with a count (`started dirt_sample ×57`).
- **ended**: the synths that are gone, newest first. Click one to see what it
  was and which controls it had - useful for a sound that lasted a blink.
- **The strip** above the list is the last 90 seconds: blue bars up are nodes
  starting, grey bars down are nodes ending, an orange tick is a problem, a red
  tick is a FAILURE.

**To go back in time:** press space (the window holds still), then click the
strip, or press left and right. The list, the levels and the problems are those
of that moment. Press space again to follow the server.

## 8. What changed?

1. Click **snapshots → take a snapshot now**.
2. Change something, or wait for the bug.
3. Click **snapshots → what changed since** and pick the snapshot.

The **changes** view lists nodes added, removed and moved, controls that
changed (`freq 300 → 777`), busses that went silent. The same menu looks at an
old snapshot again, compares the last two, and writes one to disk as a text
file you can read or send to someone.

## 9. Keep an eye on a value

- Press **w** beside a control, or **watch** on a bus: the value stays under the
  list with a small graph of its last moments. Click its **×** to remove it.
- **buffers** (view menu) lists the buffers and which synths read them.

## 10. While a pattern plays

With **calm** on (the default) the window stays readable under a fast pattern:

- rows move at most every 0.7 s, and the row you are looking at stays in place;
- a group that makes many short-lived nodes shows them as **one line**
  (*short-lived nodes: 3 here now, about 16 a second*); click it to see them one
  by one;
- each of those nodes is still in **log** and **ended**.

Turn **calm** off to see every node as it comes.

## Keys and clicks

| | |
|---|---|
| space | hold still / follow the server |
| left, right | held still: step back and forward |
| up, down | move the selection |
| return | fold or unfold the selected group |
| escape | close the window |
| click a row | inspect it |
| double click a synth | trace it |
| double click a bus or a path step | follow the signal from there |
| double click a problem or a log line | go to that node in the tree |
| click the triangle | fold a group |
| mouse wheel | scroll |

## From code

```supercollider
t = SPTree(s);
t.mode = \busses;                     // \tree \busses \problems \path \log \recent \buffers \diff
t.inspect(1001);                      // select a node and show it
t.showPath(1001);                     // where its sound goes
t.problems.do { |p| p[\text].postln };
t.snapshot;
t.compare(t.snapshots.first);         // what changed since
t.saveSnapshot;                       // to SPSnapshot.folder
t.filter = "dirt";
SPWindow.showHints = false;           // fold the hint bar away
```

## What it can not tell you

- The busses of a synth whose SynthDef was not made in this SuperCollider
  (with `.add`) are unknown: the busses view says how many such synths there are.
- A light shows a **bus**, not one synth: two synths that write the same bus share it.
- **listen** adds the bus to what already plays; it is not a solo.
- The server has no CPU figure per synth.
