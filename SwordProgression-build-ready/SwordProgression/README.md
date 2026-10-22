# SwordProgression — prototype source (Paper 1.21.11)

A Java 21 Paper plugin source project. This is a **prototype**, not a production-ready plugin.

## Included
- First-join randomized 5-second title/particle roll, and OP rerolls
- Six named swords with distinct color themes
- Persistent per-player sword and energy data in players.yml
- Progression at 5 and 15 energy, PvP kill rewards, anti-farming cooldown
- OP `/energy give|set|reset` and `/sword list|reroll|change|progression|give`
- Three ability inputs with basic combat effects, particles and cooldowns
- Soulbound sword protection for common drop/death/inventory actions

## Not complete
- **No custom model resource pack**; currently netherite sword visuals only.
- Abilities are simple shared prototypes, not all 18 individually scripted mechanics.
- No rigorous exploit-proof inventory system, no automated tests or live server validation.
- Some client input interactions (shift-left-click) may need additional testing or a packet-based input approach.
- No actionbar cooldown HUD or fully polished rolling deceleration.

## Build
Install Java 21 and Gradle 8.10+ or use an equivalent Gradle wrapper. Run `gradle build` from this folder with internet access to Paper's Maven repository. Output should be `build/libs/SwordProgression-0.1.0.jar`.

## Installation
Put the compiled JAR into a Paper 1.21.11 server's `plugins` folder and restart. The server needs a compatible Java runtime. OPs can run `/sword list`, `/sword reroll`, `/sword progression 3`, and `/energy give <player> 15`.

## Build verification
The build **was not compiled or run** in the authoring environment because the Paper API dependency and Gradle are not locally available and network resolution is disabled. Compilation and runtime behavior are unverified.
