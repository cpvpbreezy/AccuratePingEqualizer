<p align="center">
  <img src="art/logo-512.png" width="160" alt="Accurate Ping Equalizer logo">
</p>

<h1 align="center">Accurate Ping Equalizer</h1>

<p align="center">
  Play at an <b>exact</b> ping on any server, so a 6&nbsp;ms server set to 65&nbsp;ms plays like a server you really have 65&nbsp;ms on.<br>
  Client-side Fabric mod for Minecraft <b>1.21.11</b>. Built for crystal PvP.
</p>

---

## What it does

Accurate Ping Equalizer holds your packets back so your total ping lands on a number you choose. You'd use it to:

- practise at the ping you'll actually play a tournament or ranked match at,
- make two players' pings equal for a fair fight,
- copy how a far-away server feels on a close one.

It is a fork of [Ravenclaw's Ping Equalizer](#credits), rebuilt to be accurate. That matters most at the
low pings people actually play at, and in fast combos (crystals, anchors, sword hits, block place/break).

## Commands

| Command | What it does |
|---|---|
| `/ape total <ms>` | Make your **total** ping exactly `<ms>` (it measures your real ping and adds the difference). |
| `/ape add <ms>` | Add a fixed `<ms>` on top of your real ping. |
| `/ape capture` | On a server you want to copy: measures your real ping **and how much it wobbles** for 10 s and saves it. |
| `/ape match` | On another server: play with exactly the captured ping and wobble. |
| `/ape jitter <ms>` | Set the wobble by hand (`0` = perfectly steady, the default). |
| `/ape status` | Real (base) ping, added delay, total, live timing error, combat lock. |
| `/ape off` | Turn it off. |

`/accuratepingequalizer` works as a long alias. Changing your ping is announced in chat (`[Ping Equalizer] ...`),
same as the original, so nobody is equalizing in secret. The commands are blocked while chat is set to "commands only".

## Key differences from Ravenclaw's Ping Equalizer

| | Ping Equalizer (original) | **Accurate Ping Equalizer** |
|---|---|---|
| Minecraft version | Built for 1.21; on 1.21.11 several of its network hooks no longer match the game and silently do nothing | Built and tested on **1.21.11** |
| How your real ping is measured | Measures the round trip **including its own added delay**, then subtracts what it *meant* to add. Any timing error feeds straight back into the estimate | Dedicated probe packets that **skip the delay** and are timed right at the network edge, so it measures the true network ping, independent of the delay |
| Starting value for `total` | Seeds from the tab-list ping, which already **includes the old added delay** when you change targets (e.g. 200 → 300 starts from a base of ~200) | Real ping is already known, so a new target is correct **instantly**; first-time use converges within ~0.3 s |
| Following changes in your real ping | Slow average (≈7 % per sample, max 25 ms steps, a sample every ~1.5 s), so a wrong estimate can take **minutes** to recover | Robust trimmed average over the last 5 s plus instant re-sync on a real route change; drift is followed smoothly at ≤40 ms/s |
| Timer | Netty scheduler (ms-granular on Windows) plus 2 ms busy-waits **on the network thread itself** | Dedicated high-priority pacing thread with a busy-wait window **calibrated to your PC's timer**; network thread never blocked |
| Self-correction | None: whatever the OS timer adds is added to your ping | Measures how late every packet actually left and **corrects it automatically** (closed loop), so the average lands on target |
| Delay resolution | Rounded to 2 ms steps, re-adjusted up to every 150 ms (even mid-fight) | Sub-millisecond, and **frozen during combat** (see below) |
| Combat | Not handled specially | **Combat lock:** after a hit, crystal place/break, anchor place/charge/detonate, block place/break, hotbar swap or taking damage, the delay is frozen for 3 s, so every action in a combo reaches the server with the same delay and the **exact spacing you made it with**. Combat packets also get extra-precise timing |
| Max added delay | 400 ms | 1000 ms |
| Feel of a real connection | Always a perfectly flat line | Optional wobble that copies a real server (`/ape capture` → `/ape match`), keeping TCP-style packet order and bunching |
| "Commands only" chat check | Uses reflection on a private field; never actually triggers | Works |
| Installed together | n/a | Can be installed **alongside** the original: it uses `/ape` (the original keeps `/pe`), shares the server channels instead of crashing, and turning one on turns the other off so delays never stack |

### Why low pings felt wrong

At high targets, a few ms of error is a small fraction of the total. At 40–80 ms it's very noticeable. Three things caused it:

1. The base-ping estimate in the original drifted (it was measured through its own delay), and at low targets that error is a big share of the total.
2. Timer overshoot on Windows added a few unaccounted milliseconds to every packet.
3. A real 65 ms connection is not a flat 65 ms. It wobbles by a few ms, and that wobble is a big part of how a low ping "feels". `/ape capture` + `/ape match` reproduces it.

## Works with servers

Servers that already support the original mod keep working. The control channels
(`ravenclawspingequalizer:control`, `state_query`, `state_response`) are kept as-is, so a server can still
**turn the equalizer off** or **ask what it's set to**. When disabled by the server, `/ape` refuses to turn on.

Always follow the rules of the server you play on. Some servers do not allow any added latency.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) for 1.21.11 and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Put `accurate-ping-equalizer-<version>+1.21.11.jar` in your `mods` folder.

## Building

```sh
./gradlew build          # jar in build/libs/
```

Requires JDK 21.

## How it was tested

Every version is checked with a real Fabric client (production launch, not a dev environment) on a test
server that records, as the **server** sees it, the player's round-trip time (what the tab list and most
anticheats use) and the exact arrival time of every combat packet. Scripted runs cover a range of target
pings, crystal/anchor-style combos (one action per tick) and connections with simulated internet jitter.

## Credits

- **Original mod:** [Ping Equalizer](https://modrinth.com/) by **Ravenclaw**, MIT License.
  The server-control protocol and the public chat announcement come from it.
- **Accurate Ping Equalizer:** cpvpbreezy.

MIT License. See [LICENSE.txt](LICENSE.txt). Both copyright notices are kept as the license requires.
