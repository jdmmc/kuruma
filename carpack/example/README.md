# Making a car pack

**English** | [日本語](README-ja.md)

**You don't need Java to add a car to Kuruma.** Build a single mod jar that contains nothing
but JSON and models, drop it in `mods/`, and the new cars show up.

This `carpack/example/` folder is a **working example** that adds one car and one wheel part.

| Id | What it demonstrates |
|---|---|
| `kurumamod_example_carpack:sample` | The full set — its own body mesh, texture and lights |
| `kurumamod_example_carpack:deep_dish` | A wheel part: the defaults it can hand the fitment sliders |

(`carpack/basic/` next door is the official pack that ships to players. It is the same
layout with the documentation stripped out — a good look at what a finished pack contains.)

**This example is free to use.**
Copy it as the base of your own pack. No credit, no permission needed.

---

## 1. Run it first

Zip the contents of this folder, rename it to `.jar`, and put it in `mods/`.

```bash
cd carpack/example
jar --create --file ../kurumamod_example_carpack.jar .     # if you have a JDK
zip -r ../kurumamod_example_carpack.jar .                  # zip works just as well
```

```powershell
# Windows, without a JDK
Compress-Archive -Path * -DestinationPath ..\kurumamod_example_carpack.zip
Rename-Item ..\kurumamod_example_carpack.zip kurumamod_example_carpack.jar
```

**`META-INF/` and `pack.mcmeta` must sit at the top level of the jar.** Zipping the folder
itself buries them one level deeper, and nothing happens when you load it. That is why the
commands above run from **inside** `example`.

Start the game and open the creative tab: the sample car is there.

## 2. Make it your own pack

Copy the example and replace `kurumamod_example_carpack` with your own name and `sample` with your own car
name. That is very nearly the whole job.

```
carpack/example/
├─ META-INF/mods.toml                              ← modId is decided here
├─ pack.mcmeta
├─ kurumamod_example_carpack.png                   ← image in the mod list (logoFile in mods.toml)
├─ data/kurumamod_example_carpack/cars/sample.json ← spec (how it drives)
└─ assets/kurumamod_example_carpack/
   ├─ vehicles/sample.json                         ← looks
   ├─ lang/{ja_jp,en_us}.json                      ← display name
   ├─ models/entity/sample.obj + .mtl              ← body mesh
   └─ textures/entity/sample.png
```

(`README.md`, `README-ja.md` and `CURSEFORGE.md` document this example — keep them or delete
them. `CURSEFORGE.md` doubles as a template for a distribution page.)

**Once you pick the car id, every other name follows from it.** For `mypack:ae86`:

| | Name |
|---|---|
| Spec | `data/mypack/cars/ae86.json` |
| Looks | `assets/mypack/vehicles/ae86.json` |
| Translation key for the display name | `car.mypack.ae86` (write it in lang) |

A mismatch produces no error. It silently falls back to the default.
If the spec is there but the looks file is named differently, the car drives but wears the
default body. Lining these up matters more than anything else here.

Rules:

- **No uppercase, no spaces.** Namespaces allow `a-z 0-9 _ . -`; car names allow those plus `/`
- **Make the namespace (`mypack`) the same as `modId` in `mods.toml`.** It works either way,
  but if two packs collide you will not be able to tell which one won
- **`modId` is stricter than a namespace: no `-`, no `.`** (`^[a-z][a-z0-9_]{1,63}$`). Breaking
  that fails the load with `Invalid modId`, so **use `_` as the only separator** if you want the
  two to match (as this example's `kurumamod_example_carpack` does)
- **Don't use subfolders.** `cars/toyota/ae86.json` becomes the id `mypack:toyota/ae86`, and
  the translation key turns into the ugly `car.mypack.toyota/ae86`
- Without a lang entry, the display name is simply `ae86`

## 3. Define the spec (`data/<pack>/cars/<car>.json`)

**Better than typing numbers by hand: build the car in-game and export it.**

1. Get in a car, press **G** to open the tuning screen, and dial it in
2. Save it under a name from "Preset"
3. Copy that one entry out of `config/kurumamod-setups.json` into the `values` of
   `cars/<car>.json`

**Anything you leave out falls back to the default.** Write only what differs from the default
car — and old packs keep loading when Kuruma adds new parameters.

Keys starting with `_` are skipped, so `"_comment": "..."` is how you leave a note (JSON has
no comments).

The full list of parameters is in [Appendix B](#b-spec--datapackcarscarjson).

### Internal units. **Angles are radians**

Even for parameters the tuning screen shows in degrees, the file wants radians. Writing `32.0`
into `max_steer` "meaning 32 degrees" gets you **32 radians**, with no error and no warning
(the default is 35 degrees = `0.6109`).
**You cannot catch that by eyeballing the magnitude, so exporting from in-game is the safe path.**

| Quantity | Unit |
|---|---|
| Length | m (not cm) |
| Mass | kg |
| Angle | **rad** (not degrees) |
| Spring rate | N/m |
| Torque | N·m |
| Engine speed | rpm |

## 4. Define the looks (`assets/<pack>/vehicles/<car>.json`)

**Never put spec values here.** Everything that decides behaviour lives on the `data/` side
only.

Anything you leave out falls back to the default, so you do not have to write all of it. The
full list is in [Appendix A](#a-looks--assetspackvehiclescarjson).

The essentials:

- **Models and textures are referenced with a namespace**, so you can borrow Kuruma's instead
  of making your own (the sample car borrows Kuruma's wheels)
- `designWheelBase` / `designWheelRadius` mean "**what size the mesh was built at**", not the
  size of this car. The real dimensions live in the spec, and the mesh is scaled by the ratio
- The seat position is decided here too (the driver sits on the right)

## 5. Build the mesh (Blender)

**The Blender side has a manual of its own** — [Vehicle Modeling Manual](https://static.jdm-mc.com/manual/MODELING.html).
The same page sits **right here in this folder as `MODELING.html`** — open it in a browser and
it works offline. It covers lining up the origin, the object names that carry meaning (lights,
glass, mirrors), the traps in the export settings and a symptom-by-symptom troubleshooting
table, with screenshots. **If you have never used Blender, start there.**

What follows is only the summary:

- Units are **metres**. There is no rule about overall length, width or height
- **The body origin is: ground level, midpoint between the axles, centred left to right**
- **Build one left-hand wheel only** (the right side is drawn mirrored)
- Triangulate before exporting. Blender's default export settings are fine

A gauge for matching dimensions is in `tools/blender_gauge.py`. Build against it and you never
need to fiddle with scale factors in JSON (`sample.obj` was made this way).
To use it in Blender: Scripting → New → (paste) → Run. Hide it when you export.

### Lights are decided by object name

Object names inside `car.obj` (the Blender object name carries straight through):

| Name | Treated as |
|---|---|
| `light_head` | Headlights. Drawn at full brightness when on, and the origin of the light beams |
| `light_tail` | Tail lights. Dim at all times, full under braking |
| `light_reverse` | Reverse lights. When the gear is R |
| Anything else | Body |

**Do not invent new `light_` names.** Body rendering excludes **everything** starting with
`light_`, yet only the three above are ever drawn — so a name like `light_fog` becomes a hole
where nothing is rendered at all.

**One object per role is enough; you do not need to split left and right** — a mirror modifier
works as is. The position and direction of the light beams are read from the mesh, so no
coordinates are needed in the JSON.

**Which side decides the lens colour depends on whether you UV unwrapped it.**

| Lens UV | Colour comes from |
|---|---|
| Collapsed to a point | **Kuruma** (warm white for head, red for tail, white for reverse). Just point it at one white pixel of the texture |
| Unwrapped | **The texture**. What you painted in Blender is what you get |

The colour is never applied twice (unwrapped lenses get white). **On/off brightness is owned by
Kuruma either way**, so there is no need to bake a dark state into the texture.

## 6. When it does not work

**A car pack fails quietly** — broken JSON or a mismatched name only drops that one entry and
carries on. Work backwards from the symptom:

| Symptom | Where to look |
|---|---|
| Does not even appear in the mod list | Is `META-INF/mods.toml` at the top level of the jar (did you zip the folder itself)? |
| No cars added at all | Does `data/<pack>/cars/*.json` exist? Check the `車種を N 件読み込みました` line in the log |
| Only that one car missing | That JSON is broken. The reason is in the log |
| Drives, but wears the default body | Does the filename under `assets/<pack>/vehicles/` match the spec? |
| Display name shows as `ae86` | Is the translation key `car.<pack>.<car>`? |
| Body and wheels do not line up | Are `designWheelBase` / `designWheelRadius` the mesh's real dimensions? |
| Steering is wild, or does nothing | Did you write an angle in degrees? (**radians**) |

While you are dialling things in, **`/reload` reloads the spec and F3+T reloads the looks** —
no need to restart the game.

**Removing a car pack breaks nothing.** Unknown car ids drive as the default car, and the id is
remembered, so putting the pack back restores them.

---

## Appendix: every key you can write

**Anything you leave out falls back to the default**, so you never have to write all of it.
This is a reference for *what exists*; how to dial it in is in sections 3 and 4.

### A. Looks — `assets/<pack>/vehicles/<car>.json`

That is all of it (14 keys). Any key not listed here is ignored.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `body.model` | id | `kurumamod:models/entity/s15.obj` | Body OBJ |
| `body.texture` | id | `kurumamod:textures/entity/car.png` | Body texture |
| `body.offset` | `[x,y,z]` | `[0,0,0]` | Body translation [m] (+X right, +Y up, -Z forward) |
| `body.scale` | one number or `[x,y,z]` | `1.0` | Body scale. The origin is at ground level, so it **grows upward** |
| `wheel.model` | id | `kurumamod:models/entity/wheel.obj` | Wheel OBJ |
| `wheel.texture` | id | `kurumamod:textures/entity/wheel.png` | Wheel texture |
| `wheel.offset` | `[x,y,z]` | `[0,0,0]` | Wheel translation [m]. **X points outward** (the sign flips per side) |
| `wheel.scale` | one number or `[x,y,z]` | `1.0` | **X is width, Y and Z are diameter.** Touching Y/Z breaks ground contact — change diameter with the spec's `wheel_radius` instead |
| `wheel.camber` | number | `0.0` | Camber angle [**degrees**], negative = top leans in. **Visual only; it does not affect grip** |
| `designWheelBase` | number | `3.12` | **How far apart the mesh's wheel arches are [m].** Not this car's wheelbase |
| `designWheelRadius` | number | `0.45` | **What radius the mesh was built at [m].** Not this car's tyre radius |
| `designRideHeight` | number | `0.6019` | How far the body drops below the chassis reference plane [m] |
| `seat` | `[x,y,z]` | `[0.5, -0.55, 0.2]` | Driver's seat [m], same axes as `body.offset`. The passenger seat is mirrored automatically |
| `shadowRadius` | number | `1.4` | Shadow size |

### B. Spec — `data/<pack>/cars/<car>.json`

```json
{
  "order": 10,
  "values": {
    "mass": 1500.0,
    "peak_torque": 200.0
  }
}
```

- `order` — sort order in the list (default `100`)
- `values` — the spec. **You may drop it and write the values at the root instead**
- **Keys starting with `_` are skipped**, so `"_comment": "..."` can record why a value is what
  it is
- **Internal units; angles are radians** (see [Internal units](#internal-units-angles-are-radians))
- **The ranges below are the tuning screen's slider ranges, not validation.** Out-of-range
  values are accepted as written, so extreme numbers will happily produce a broken car

#### Suspension

| Name | Name on the tuning screen | Unit | Default | Range |
|---|---|---|---|---|
| `stroke` | Stroke | m | 0.25 | 0.15 – 0.80 |
| `front_anti_roll` | Front anti-roll bar | N/m | 2500 | 0 – 20000 |
| `rear_anti_roll` | Rear anti-roll bar | N/m | 1500 | 0 – 20000 |

**To stiffen the suspension, shorten `stroke`.** Static sag is fixed at 39.2% of the stroke, so
a shorter stroke means stiffer springs and dampers.

#### Tyres

| Name | Name on the tuning screen | Unit | Default | Range |
|---|---|---|---|---|
| `friction` | Grip | — | 1.0 | 0.3 – 1.5 |
| `cornering_stiffness` | Cornering power | — | 12.0 | 4 – 25 |
| `longitudinal_stiffness` | Longitudinal slip stiffness | — | 18.0 | 5 – 40 |
| `wheel_radius` | Tyre radius | m | 0.45 | 0.25 – 1.10 |

**`friction` is the actual coefficient of friction (mu).** The tuning screen shows it
relative to the default (times `CarSpec.REFERENCE_FRICTION`). That reference is 1.0 for
now, so the number on the screen is the mu, but the two part ways if it ever changes.

#### Body

| Name | Name on the tuning screen | Unit | Default | Range |
|---|---|---|---|---|
| `mass` | Mass | kg | 1200 | 500 – 2500 |
| `weight_distribution` | Weight distribution | fraction on the front axle | 0.56 | 0.35 – 0.70 |
| `wheel_base` | Wheelbase | m | 3.12 | 2.0 – 7.0 |
| `track_width` | Track width | m | 1.8 | 1.2 – 4.0 |

#### Engine

| Name | Name on the tuning screen | Unit | Default | Range |
|---|---|---|---|---|
| `peak_torque` | Peak torque | N·m | 220 | 60 – 700 |
| `peak_torque_rpm` | Peak torque rpm | rpm | 4200 | 1500 – 8000 |
| `peak_power_rpm` | Peak power rpm (its ratio to peak torque rpm sets the curve shape) | rpm | 1.59 × peak torque rpm (6676) | 2500 – 12000 |
| `idle_rpm` | Idle rpm | rpm | 800 | 500 – 2000 |
| `redline_rpm` | Redline | rpm | 7000 | 4000 – 12000 |
| `engine_brake` | Engine braking | N·m | 35 | 0 – 120 |
| `engine_inertia` | Engine inertia | kg·m² | 0.25 | 0.05 – 1.0 |

#### Gearbox

| Name | Name on the tuning screen | Unit | Default | Range |
|---|---|---|---|---|
| `gear_count` | Forward gears | gears | 5 | 3 – 8 |
| `first_gear` | 1st gear ratio | — | 3.4 | 1.5 – 5.0 |
| `top_gear` | Top gear ratio | — | 0.85 | 0.4 – 1.5 |
| `drivetrain_efficiency` | Drivetrain efficiency | — | 0.90 | 0.7 – 1.0 |
| `shift_time` | Shift time | s | 0.35 | 0 – 1.5 |
| `manual_transmission` | Transmission (0 = AT, 1 = MT) | — | 0 | 0 – 1 |

#### Driveline

| Name | Name on the tuning screen | Unit | Default | Range |
|---|---|---|---|---|
| `drive_bias` | Drive split (0 = front, 1 = rear) | — | 0.5 | 0 – 1 |
| `diff_preload` | Diff preload | N·m | 0 | 0 – 300 |
| `diff_lock_ratio` | Diff locking ratio | — | 0.0 | 0 – 1 |
| `diff_coast_ratio` | Diff coast side | — | 0.5 | 0 – 1 |

With `diff_lock_ratio` and `diff_preload` both at 0 it is an open diff. `diff_coast_ratio` is
1-way at 0, 1.5-way at 0.5, 2-way at 1.

#### Brakes and resistance

| Name | Name on the tuning screen | Unit | Default | Range |
|---|---|---|---|---|
| `abs` | ABS (0 disables it) | strength | 0.7 | 0 – 1 |
| `rolling_resistance` | Rolling resistance | — | 0.15 | 0 – 1 |
| `drag` | Drag coefficient | — | 0.0004 | 0.0001 – 0.005 |

There is no top-speed field. **The real top speed comes out of torque versus resistance**,
and the car can never go faster than the top gear at the redline.

#### Controls

| Name | Name on the tuning screen | Unit | Default | Range |
|---|---|---|---|---|
| `max_steer` | Max steering angle | **rad** | 0.6109 (= 35°) | 0.1745 – 0.8727 |
| `steer_grip_margin` | Steering margin (higher = easier to slide) | — | 1.1 | 0.8 – 8.0 |
| `visual_slip_limit` | Visual wheel angle limit (0 disables it) | **rad** | 0.7854 (= 45°) | 0 – 1.5708 |
| `steer_rate` | Time to full lock | s | 0.30 | 0 – 1 |
| `steer_return` | Time to return to centre | s | 0.18 | 0 – 1 |
| `self_aligning` | Self-aligning steering (0 = returns to centre) | — | 0.0 | 0 – 1 |
| `pedal_press` | Time to fully press a pedal | s | 0.25 | 0 – 1 |
| `pedal_release` | Time to fully release a pedal | s | 0.12 | 0 – 1 |
| `traction_control` | Traction control (0 disables it) | strength | 1.0 | 0 – 1 |

### C. What is ignored even if you write it

**Values that follow from other values are silently dropped.** Kuruma computes them:

| Derived | From |
|---|---|
| Springs, dampers, bump stops | `mass` / `weight_distribution` / `stroke` |
| Centre of gravity height | Static ride height |
| All three moments of inertia | `mass` with `wheel_base` / `track_width` |
| Wheel inertia, climbable step, final drive | `wheel_radius` |
| Brake bias | `weight_distribution`, CG height and wheelbase |
| Brake and handbrake authority | `friction` |
| Shift points, reverse top speed | `redline_rpm` and the gear ratios |

So there is no cross-checking to do — no "I changed the tyre radius, now fix the final drive",
no "I changed the dimensions, now fix the inertia". Change the radius and the gearing, ride
height and climbing ability all move with it.
