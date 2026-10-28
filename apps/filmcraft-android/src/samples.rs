//! Sample pictures for the effect and transition pickers: FilmCraft's own procedural demo scenes
//! with the effect, or a cut between two of them with the transition part-way through. They don't
//! depend on the user's clips, so the app can make them once and keep them.

use std::collections::HashMap;
use std::sync::Arc;

use filmcraft_media::generators::GeneratorSource;
use filmcraft_media::{DemoScene, Generator, MediaSource, SharedSource};
use filmcraft_project::{ItemId, ItemKind, Label, MediaClip, MediaRef, Project, SequenceSettings, TrackKind, Transition, TransitionId};
use filmcraft_render::RenderOptions;
use filmcraft_time::{FrameRate, Tick, TimeRange};

use crate::{Frame, MobileError, presets};

const SIDE: u32 = 192;
const RATE: FrameRate = FrameRate::FPS_24;

struct Sample {
    project: Project,
    seq: ItemId,
    sources: HashMap<ItemId, SharedSource>,
}

fn add_scene(s: &mut Sample, scene: DemoScene, seconds: f64) -> ItemId {
    let dur = Tick::from_seconds_f64(seconds);
    let src = GeneratorSource::new(Generator::Demo(scene), SIDE, SIDE, RATE, dur);
    let clip = MediaClip {
        media: MediaRef::Generator(Generator::Demo(scene)),
        info: src.info().clone(),
        interpret: Default::default(),
        mark_in: None,
        mark_out: None,
        markers: vec![],
        offline: false,
        proxy: None,
        identity: None,
    };
    let item = s.project.add_item("sample", Label::Iris, ItemKind::Media(Box::new(clip)), None);
    s.sources.insert(item, Arc::new(src));
    item
}

fn sample() -> Sample {
    let mut project = Project::new("sample");
    let seq = project.new_sequence("sample", SequenceSettings { width: SIDE, height: SIDE, frame_rate: RATE, ..Default::default() }, 1, 1, None);
    Sample { project, seq, sources: HashMap::new() }
}

fn place(s: &mut Sample, item: ItemId, start: Tick, seconds: f64) -> Result<filmcraft_project::ClipId, MobileError> {
    let dur = Tick::from_seconds_f64(seconds);
    let mut v = s
        .project
        .make_track_item(item, TrackKind::Video, start, TimeRange::new(Tick::ZERO, dur), RATE)
        .ok_or_else(|| MobileError::Render("sample clip".into()))?;
    for e in &mut v.effects {
        filmcraft_project::resolve_auto_points(e, (SIDE, SIDE), (SIDE, SIDE));
    }
    let id = v.id;
    let q = s.project.sequence_mut(s.seq).ok_or(MobileError::NoSequence)?;
    let t = q.video_tracks.first_mut().ok_or(MobileError::NoSequence)?;
    t.items.push(v);
    t.sort();
    Ok(id)
}

fn draw(s: Sample, t: Tick, max_side: u32) -> Frame {
    let scale = (max_side.clamp(16, SIDE) as f32 / SIDE as f32).min(1.0);
    let sources = s.sources;
    let provider = move |id: ItemId| sources.get(&id).cloned();
    let img = filmcraft_render::render_sequence(&s.project, s.seq, t, RenderOptions { scale, ..Default::default() }, &provider);
    let (width, height) = (u32::try_from(img.w).unwrap_or(0), u32::try_from(img.h).unwrap_or(0));
    Frame { width, height, time: t.0, rgba: img.over_black_rgba8() }
}

/// A demo scene with effect `effect` at `amount` (0–100).
#[uniffi::export]
pub fn effect_sample(effect: String, amount: f64, max_side: u32) -> Result<Frame, MobileError> {
    let mut s = sample();
    let item = add_scene(&mut s, DemoScene::OceanSunset, 2.0);
    let clip = place(&mut s, item, Tick::ZERO, 2.0)?;
    if !effect.is_empty() {
        let mut inst = presets::effect_instances(&effect, amount).ok_or_else(|| MobileError::Command(format!("Unknown effect {effect}.")))?;
        for e in &mut inst {
            filmcraft_project::resolve_auto_points(e, (SIDE, SIDE), (SIDE, SIDE));
        }
        let q = s.project.sequence_mut(s.seq).ok_or(MobileError::NoSequence)?;
        let c = q.find_item_mut(clip).map(|x| x.1).ok_or(MobileError::NoSequence)?;
        let pos = c.effects.iter().position(|e| e.def().is_some_and(|d| d.intrinsic)).unwrap_or(c.effects.len());
        for (k, e) in inst.into_iter().enumerate() {
            c.effects.insert(pos + k, e);
        }
    }
    // part-way in, where moving effects show
    Ok(draw(s, Tick::from_seconds_f64(0.7), max_side))
}

/// A cut between two demo scenes with transition `preset` `progress` (0–1) of the way through.
#[uniffi::export]
pub fn transition_sample(preset: String, progress: f64, max_side: u32) -> Result<Frame, MobileError> {
    let mut s = sample();
    let a = add_scene(&mut s, DemoScene::Dunes, 2.0);
    let b = add_scene(&mut s, DemoScene::Aurora, 2.0);
    let ca = place(&mut s, a, Tick::ZERO, 1.0)?;
    let cut = Tick::from_seconds_f64(1.0);
    let cb = place(&mut s, b, cut, 1.0)?;
    let dur = Tick::from_seconds_f64(1.0);
    let start = cut - Tick(dur.0 / 2);
    if !preset.is_empty() {
        let effect = presets::transition_instance(&preset).ok_or_else(|| MobileError::Command(format!("Unknown transition {preset}.")))?;
        let id = TransitionId(s.project.alloc_id());
        let q = s.project.sequence_mut(s.seq).ok_or(MobileError::NoSequence)?;
        let t = q.video_tracks.first_mut().ok_or(MobileError::NoSequence)?;
        t.transitions.push(Transition { id, effect, start, duration: dur, from: Some(ca), to: Some(cb), align: Default::default(), reverse: false });
    }
    let k = if progress.is_finite() { progress.clamp(0.0, 1.0) } else { 0.4 };
    // a frame short of the end: the last tick already belongs to the next clip alone
    let at = Tick::from_seconds_f64(k * dur.seconds()).min(dur - Tick(1));
    Ok(draw(s, start + at, max_side))
}
