use super::*;
use deckcraft_model::ShapeKind;

struct TempDirGuard(PathBuf);

impl Drop for TempDirGuard {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn temp_env(tag: &str) -> (TempDirGuard, PathBuf, PathBuf) {
    let suffix = format!(
        "deckcraft_reg_{tag}_{}_{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or_default()
    );
    let root = std::env::temp_dir().join(suffix);
    let files = root.join("files");
    let cache = root.join("cache");
    std::fs::create_dir_all(&files).unwrap();
    std::fs::create_dir_all(&cache).unwrap();
    (TempDirGuard(root), files, cache)
}

fn make_raw(
    width_pt: f32,
    height_pt: f32,
    native_ppp: f32,
    events: Vec<egui::Event>,
    time: f64,
) -> egui::RawInput {
    egui::RawInput {
        screen_rect: Some(egui::Rect::from_min_size(
            egui::Pos2::ZERO,
            egui::Vec2::new(width_pt, height_pt),
        )),
        time: Some(time),
        focused: true,
        events,
        viewports: std::iter::once((
            egui::ViewportId::ROOT,
            egui::ViewportInfo {
                native_pixels_per_point: Some(native_ppp),
                focused: Some(true),
                inner_rect: Some(egui::Rect::from_min_size(
                    egui::Pos2::ZERO,
                    egui::Vec2::new(width_pt, height_pt),
                )),
                ..Default::default()
            },
        ))
        .collect(),
        ..Default::default()
    }
}

fn step(
    core: &mut ThreadBoundCore,
    width_pt: f32,
    height_pt: f32,
    events: Vec<egui::Event>,
    t: &mut f64,
) -> egui::FullOutput {
    *t += 0.016;
    let mut out = core.run_single_pass(make_raw(width_pt, height_pt, 1.0, events, *t));
    out.textures_delta.clear();
    out
}

fn collect_all_labeled_nodes(
    out: &egui::FullOutput,
) -> Vec<(String, egui::accesskit::Role, Option<egui::Rect>, bool)> {
    let mut all = Vec::new();
    if let Some(update) = &out.platform_output.accesskit_update {
        for (_id, node) in &update.nodes {
            if let Some(label) = node.label()
                && !label.is_empty()
            {
                let rect = node.bounds().map(|b| {
                    egui::Rect::from_min_max(
                        egui::pos2(b.x0 as f32, b.y0 as f32),
                        egui::pos2(b.x1 as f32, b.y1 as f32),
                    )
                });
                all.push((label.to_string(), node.role(), rect, node.is_disabled()));
            }
        }
    }
    all
}

fn find_rect(out: &egui::FullOutput, label: &str) -> Option<egui::Rect> {
    collect_all_labeled_nodes(out)
        .into_iter()
        .find(|(l, _, _, _)| l == label)
        .and_then(|(_, _, rect, _)| rect)
}

fn collect_shape_text(shape: &egui::Shape, out: &mut Vec<(String, egui::Rect)>) {
    match shape {
        egui::Shape::Text(t) => {
            let s = t.galley.text().to_string();
            if !s.is_empty() {
                out.push((s, t.galley.rect.translate(t.pos.to_vec2())));
            }
        }
        egui::Shape::Vec(v) => {
            for s in v {
                collect_shape_text(s, out);
            }
        }
        _ => {}
    }
}

fn collect_text_shapes(out: &egui::FullOutput) -> Vec<(String, egui::Rect)> {
    let mut texts = Vec::new();
    for cs in &out.shapes {
        collect_shape_text(&cs.shape, &mut texts);
    }
    texts
}

fn setup_core_with_new_deck(tag: &str, width_pt: f32, height_pt: f32) -> (TempDirGuard, ThreadBoundCore, f64) {
    let (guard, files, cache) = temp_env(tag);
    let wake = Arc::new(AtomicBool::new(true));
    let mut core = ThreadBoundCore::new(
        files.to_str().unwrap(),
        cache.to_str().unwrap(),
        None,
        wake,
    );
    core.ctx.enable_accesskit();
    core.execute_host_command("file.new");
    let mut t = 0.1;
    for _ in 0..3 {
        let _ = step(&mut core, width_pt, height_pt, vec![], &mut t);
    }
    (guard, core, t)
}

fn push_touch_events(
    core: &mut ThreadBoundCore,
    pos: egui::Pos2,
    same_frame: bool,
    width_pt: f32,
    height_pt: f32,
    t: &mut f64,
) -> (egui::FullOutput, egui::FullOutput) {
    let zoom = core.ctx.zoom_factor().max(0.1);
    let native_pos = egui::pos2(pos.x * zoom, pos.y * zoom);
    let mut down = Vec::new();
    for item in [
        QueuedInput::PointerMoved {
            x_native_pt: native_pos.x,
            y_native_pt: native_pos.y,
        },
        QueuedInput::PointerButton {
            x_native_pt: native_pos.x,
            y_native_pt: native_pos.y,
            button: 0,
            pressed: true,
            modifiers: 0,
        },
        QueuedInput::Touch {
            device_id: 1,
            pointer_id: 0,
            phase: 0,
            x_native_pt: native_pos.x,
            y_native_pt: native_pos.y,
            force: 1.0,
        },
    ] {
        translate_queued_input(item, zoom, &mut core.applied_modifiers, &mut down);
    }

    let mut up = Vec::new();
    for item in [
        QueuedInput::PointerMoved {
            x_native_pt: native_pos.x,
            y_native_pt: native_pos.y,
        },
        QueuedInput::PointerButton {
            x_native_pt: native_pos.x,
            y_native_pt: native_pos.y,
            button: 0,
            pressed: false,
            modifiers: 0,
        },
        QueuedInput::Touch {
            device_id: 1,
            pointer_id: 0,
            phase: 2,
            x_native_pt: native_pos.x,
            y_native_pt: native_pos.y,
            force: 0.0,
        },
    ] {
        translate_queued_input(item, zoom, &mut core.applied_modifiers, &mut up);
    }

    if same_frame {
        down.extend(up);
        let o1 = step(core, width_pt, height_pt, down, t);
        let o2 = step(core, width_pt, height_pt, vec![], t);
        (o1, o2)
    } else {
        let o1 = step(core, width_pt, height_pt, down, t);
        let o2 = step(core, width_pt, height_pt, up, t);
        (o1, o2)
    }
}

#[test]
fn step_headless_defers_pointer_gone_on_touch_right_click_and_keeps_context_menu_open() {
    let (_guard, files, cache) = temp_env("ctx_menu");
    let engine = EngineEntry::new(files.to_str().unwrap(), cache.to_str().unwrap(), None);
    engine.execute_command("file.new".to_string());
    let mut t = 0.0;
    for _ in 0..3 {
        let _ = engine.step_headless_or_gl(false, t, 1280, 800, 1.0, true, true);
        t += 0.016;
    }

    // Exact Android single-finger rightClickLatch event sequence:
    // Moved -> Secondary Down -> Secondary Up -> PointerGone.
    engine.enqueue_input(QueuedInput::PointerMoved {
        x_native_pt: 700.0,
        y_native_pt: 450.0,
    });
    engine.enqueue_input(QueuedInput::PointerButton {
        x_native_pt: 700.0,
        y_native_pt: 450.0,
        button: 1,
        pressed: true,
        modifiers: 0,
    });
    engine.enqueue_input(QueuedInput::PointerButton {
        x_native_pt: 700.0,
        y_native_pt: 450.0,
        button: 1,
        pressed: false,
        modifiers: 0,
    });
    engine.enqueue_input(QueuedInput::PointerGone);

    let fx1 = engine.step_headless_or_gl(false, t, 1280, 800, 1.0, true, true);
    assert_eq!(
        fx1.repaint_after_ms, 0,
        "deferring trailing PointerGone must request immediate next-frame flush"
    );
    THREAD_CORES.with(|cell| {
        let map = cell.borrow();
        let core = map.get(&engine.handle()).unwrap();
        assert!(
            !core.deferred_events.is_empty(),
            "PointerGone should be held until the frame after button release"
        );
    });

    t += 0.016;
    let _fx2 = engine.step_headless_or_gl(false, t, 1280, 800, 1.0, true, true);
    THREAD_CORES.with(|cell| {
        let map = cell.borrow();
        let core = map.get(&engine.handle()).unwrap();
        assert!(core.deferred_events.is_empty(), "deferred PointerGone should flush");
        assert!(
            egui::Popup::is_any_open(&core.ctx),
            "touch secondary click over canvas must open slide context menu"
        );
    });

    // Context menu must remain open on an idle follow-up frame.
    t += 0.016;
    let _fx3 = engine.step_headless_or_gl(false, t, 1280, 800, 1.0, true, true);
    THREAD_CORES.with(|cell| {
        let map = cell.borrow();
        let core = map.get(&engine.handle()).unwrap();
        assert!(
            egui::Popup::is_any_open(&core.ctx),
            "context menu must stay open on subsequent frames"
        );
    });
}

#[test]
fn touch_same_frame_tap_on_insert_table_grid_inserts_table() {
    let (_guard, mut core, mut t) = setup_core_with_new_deck("table_grid", 1280.0, 800.0);
    let home_out = step(&mut core, 1280.0, 800.0, vec![], &mut t);
    let insert_rect = find_rect(&home_out, "Insert").expect("Insert tab");
    let _ = push_touch_events(&mut core, insert_rect.center(), false, 1280.0, 800.0, &mut t);
    // Allow one settle frame so the Insert ribbon ScrollArea computes final layout rects.
    let ins_out = step(&mut core, 1280.0, 800.0, vec![], &mut t);

    let table_rect = find_rect(&ins_out, "Table").expect("Table ribbon button");
    let (_, _o2) = push_touch_events(&mut core, table_rect.center(), false, 1280.0, 800.0, &mut t);
    let o3 = step(&mut core, 1280.0, 800.0, vec![], &mut t);
    let table_texts = collect_text_shapes(&o3);
    let insert_table_rect = table_texts
        .iter()
        .find(|(s, _)| s == "Insert Table…")
        .map(|(_, r)| *r)
        .expect("Table popup must render grid and 'Insert Table…'");

    // Tap inside the grid cells above "Insert Table…" on a same-frame finger tap.
    let cell_pos = egui::pos2(insert_table_rect.left() + 45.0, insert_table_rect.top() - 40.0);
    let shapes_before = core.app.session.active().unwrap().doc.slides[0].shapes.len();
    let _ = push_touch_events(&mut core, cell_pos, true, 1280.0, 800.0, &mut t);
    let _ = step(&mut core, 1280.0, 800.0, vec![], &mut t);

    let shapes = &core.app.session.active().unwrap().doc.slides[0].shapes;
    assert!(
        shapes.len() > shapes_before && shapes.iter().any(|s| matches!(s.kind, ShapeKind::Table(_))),
        "same-frame touch tap on Table grid must insert a table"
    );
}

#[test]
fn narrow_phone_layout_keeps_title_bar_clear_scrolls_ribbon_tabs_and_clamps_popups() {
    let (_guard, mut core, mut t) = setup_core_with_new_deck("narrow", 412.0, 915.0);
    let phone_out = step(&mut core, 412.0, 915.0, vec![], &mut t);

    let home_qat_rect = find_rect(&phone_out, "Home").expect("Home QAT button");
    let present_rect = collect_all_labeled_nodes(&phone_out)
        .into_iter()
        .find(|(l, _, _, _)| l.trim() == "Present")
        .and_then(|(_, _, r, _)| r)
        .expect("Present title bar button");
    assert!(
        home_qat_rect.max.x < present_rect.min.x,
        "QAT buttons ({home_qat_rect:?}) must not overlap right title bar controls ({present_rect:?})"
    );
    assert!(home_qat_rect.min.x >= 0.0 && present_rect.max.x <= 412.0);

    // Scroll/page the tab bar using the overflow right chevron AND touch swipe so far-right tabs (e.g. View) enter 412px width.
    let next_chevron = find_rect(&phone_out, "Scroll ribbon tabs right").unwrap_or_else(|| {
        let top_nodes: Vec<_> = collect_all_labeled_nodes(&phone_out)
            .into_iter()
            .filter(|(_, _, r, _)| r.is_some_and(|rr| rr.top() < 90.0))
            .collect();
        panic!("narrow 412px phone viewport missing right overflow chevron; top_nodes={top_nodes:?}")
    });
    assert!(next_chevron.max.x <= 412.0 && next_chevron.min.x >= 0.0);
    let _ = push_touch_events(&mut core, next_chevron.center(), false, 412.0, 915.0, &mut t);
    let mut scrolled_out = step(&mut core, 412.0, 915.0, vec![], &mut t);
    if find_rect(&scrolled_out, "View").is_none_or(|r| r.max.x > 412.0)
        && let Some(c2) = find_rect(&scrolled_out, "Scroll ribbon tabs right")
    {
        let _ = push_touch_events(&mut core, c2.center(), false, 412.0, 915.0, &mut t);
        scrolled_out = step(&mut core, 412.0, 915.0, vec![], &mut t);
    }
    let view_rect = find_rect(&scrolled_out, "View").expect("View tab");
    assert!(
        view_rect.min.x >= 0.0 && view_rect.max.x <= 412.0,
        "View tab {view_rect:?} should be reachable within 412px width after scrolling"
    );

    // Check popup content & viewport bounds under ui_scale = 1.5 across Insert, Design, Transitions, Animations.
    let (w, h) = (1280.0f32, 800.0f32);
    core.app.ui.ui_scale = 1.5;
    for _ in 0..2 {
        let _ = step(&mut core, w, h, vec![], &mut t);
    }
    let zoom = core.ctx.zoom_factor();
    assert!((zoom - 1.5).abs() < 0.01, "expected ui_scale=1.5 to apply, got {zoom}");
    let logical_w = w / zoom;
    let logical_h = h / zoom;

    let scroll_ribbon_into_view = |core: &mut ThreadBoundCore, mut out: egui::FullOutput, label: &str, t: &mut f64| -> egui::Rect {
        let scale = if find_rect(&out, "Window title bar")
            .is_some_and(|tb| (tb.width() - w).abs() < 2.0 && (logical_w - w).abs() > 10.0)
        {
            zoom
        } else {
            1.0
        };
        for _ in 0..8 {
            let mut r = find_rect(&out, label).unwrap_or_else(|| panic!("missing ribbon button {label}"));
            r = egui::Rect::from_min_max(r.min / scale, r.max / scale);
            let left_block = find_rect(&out, "Scroll ribbon left")
                .map_or(0.0, |c| c.max.x / scale);
            let right_block = find_rect(&out, "Scroll ribbon right")
                .map_or(logical_w, |c| c.min.x / scale);
            if r.center().x > left_block + 2.0 && r.center().x < right_block - 2.0 {
                return r;
            }
            if r.center().x >= right_block - 2.0
                && let Some(cr) = find_rect(&out, "Scroll ribbon right")
            {
                let c = cr.center() / scale;
                let _ = push_touch_events(core, c, false, logical_w, logical_h, t);
                out = step(core, logical_w, logical_h, vec![], t);
                continue;
            }
            if r.center().x <= left_block + 2.0
                && let Some(cl) = find_rect(&out, "Scroll ribbon left")
            {
                let c = cl.center() / scale;
                let _ = push_touch_events(core, c, false, logical_w, logical_h, t);
                out = step(core, logical_w, logical_h, vec![], t);
                continue;
            }
            return r;
        }
        panic!("ribbon button {label} did not scroll clear of overflow chevrons")
    };

    let o_home = step(&mut core, logical_w, logical_h, vec![], &mut t);
    let ins_scale = if find_rect(&o_home, "Window title bar")
        .is_some_and(|tb| (tb.width() - w).abs() < 2.0 && (logical_w - w).abs() > 10.0)
    {
        zoom
    } else {
        1.0
    };
    let ins_r = find_rect(&o_home, "Insert").expect("Insert tab scaled");
    let _ = push_touch_events(&mut core, ins_r.center() / ins_scale, false, logical_w, logical_h, &mut t);
    let mut o_ins = step(&mut core, logical_w, logical_h, vec![], &mut t);

    for (btn, expected_text) in [
        ("Shapes", "Rectangle"),
        ("Icons", "Search icons"),
        ("Chart", "Column"),
        ("Action", "Action Settings"),
        ("WordArt", "WordArt"),
        ("Zoom", "Summary Zoom"),
    ] {
        let r = scroll_ribbon_into_view(&mut core, o_ins, btn, &mut t);
        let _ = push_touch_events(&mut core, r.center(), false, logical_w, logical_h, &mut t);
        let pop = step(&mut core, logical_w, logical_h, vec![], &mut t);
        assert!(egui::Popup::is_any_open(&core.ctx), "{btn} popup must open");
        let texts = collect_text_shapes(&pop);
        let nodes = collect_all_labeled_nodes(&pop);
        assert!(
            texts.iter().any(|(s, _)| s.contains(expected_text))
                || nodes.iter().any(|(l, _, _, _)| l.contains(expected_text)),
            "{btn} popup missing expected option '{expected_text}'; texts={:?}",
            texts.iter().map(|(s, _)| s.as_str()).take(12).collect::<Vec<_>>()
        );
        // Dismiss popup
        let _ = push_touch_events(&mut core, egui::pos2(10.0, logical_h - 10.0), false, logical_w, logical_h, &mut t);
        o_ins = step(&mut core, logical_w, logical_h, vec![], &mut t);
    }

    // Verify Design > Colors & Fonts keep Customize buttons clickable (not buried past fold) even when scaled.
    let o_back = step(&mut core, logical_w, logical_h, vec![], &mut t);
    let des_scale = if find_rect(&o_back, "Window title bar")
        .is_some_and(|tb| (tb.width() - w).abs() < 2.0 && (logical_w - w).abs() > 10.0)
    {
        zoom
    } else {
        1.0
    };
    let des_r = find_rect(&o_back, "Design").expect("Design tab");
    let _ = push_touch_events(&mut core, des_r.center() / des_scale, false, logical_w, logical_h, &mut t);
    let mut o_des = step(&mut core, logical_w, logical_h, vec![], &mut t);
    for (btn, footer) in [("Colors", "Customize Colors"), ("Fonts", "Customize Fonts")] {
        let r = scroll_ribbon_into_view(&mut core, o_des, btn, &mut t);
        let _ = push_touch_events(&mut core, r.center(), false, logical_w, logical_h, &mut t);
        let pop = step(&mut core, logical_w, logical_h, vec![], &mut t);
        assert!(egui::Popup::is_any_open(&core.ctx), "{btn} popup must open");
        let texts = collect_text_shapes(&pop);
        let nodes = collect_all_labeled_nodes(&pop);
        let footer_rect = texts
            .iter()
            .find(|(s, _)| s.contains(footer))
            .map(|(_, r)| *r)
            .or_else(|| {
                find_rect(&pop, &format!("{footer}…"))
                    .or_else(|| {
                        nodes
                            .iter()
                            .find(|(l, _, _, _)| l.contains(footer))
                            .and_then(|(_, _, r, _)| *r)
                    })
                    .map(|r| egui::Rect::from_min_max(r.min / des_scale, r.max / des_scale))
            })
            .unwrap_or_else(|| {
                panic!(
                    "{btn} must expose '{footer}…'; logical={logical_w}x{logical_h}; texts={:?}",
                    texts.iter().map(|(s, r)| (s.as_str(), *r)).collect::<Vec<_>>()
                )
            });
        assert!(
            footer_rect.max.y <= logical_h + 1.0 && footer_rect.max.x <= logical_w + 1.0,
            "{btn} footer {footer_rect:?} overflows scaled viewport {logical_w}x{logical_h}"
        );
        let _ = push_touch_events(&mut core, egui::pos2(10.0, logical_h - 10.0), false, logical_w, logical_h, &mut t);
        o_des = step(&mut core, logical_w, logical_h, vec![], &mut t);
    }
}

#[test]
fn slideshow_single_display_fullscreen_state_and_doc_window_guard() {
    let (_guard, files, cache) = temp_env("slideshow");
    let engine = EngineEntry::new(files.to_str().unwrap(), cache.to_str().unwrap(), None);
    engine.execute_command("file.new".to_string());
    let mut t = 0.0;
    let baseline = engine.step_headless_or_gl(false, t, 1280, 800, 1.0, true, true);
    assert!(!baseline.fullscreen);
    assert!(!baseline.in_slideshow);

    engine.execute_command("show.presenter".to_string());
    engine.execute_command("show.start".to_string());
    t += 0.016;
    let fx_show = engine.step_headless_or_gl(false, t, 1280, 800, 1.0, true, true);
    assert!(fx_show.fullscreen, "slideshow must request Android edge-to-edge fullscreen");
    assert!(fx_show.in_slideshow, "slideshow must report inSlideshow=true");

    // Multi-window presenter or secondary doc window must remain safely disabled on Android single-display host.
    engine.execute_command("view.newWindow".to_string());
    t += 0.016;
    let _ = engine.step_headless_or_gl(false, t, 1280, 800, 1.0, true, true);
    THREAD_CORES.with(|cell| {
        let mut map = cell.borrow_mut();
        let core = map.get_mut(&engine.handle()).unwrap();
        assert_eq!(
            core.app.services.displays.as_mut().and_then(|d| d()),
            Some(1),
            "Android host must report 1 display"
        );
        assert!(core.app.show.is_some());
        assert!(
            core.app.doc_window.is_none(),
            "view.newWindow must not leave an unrendered secondary doc_window open"
        );
    });

    // Pressing Escape exits slideshow and clears both fullscreen and inSlideshow flags.
    engine.enqueue_input(QueuedInput::Key {
        key_code: 5, // EguiKeys.ESCAPE
        pressed: true,
        repeat: false,
        modifiers: 0,
    });
    engine.enqueue_input(QueuedInput::Key {
        key_code: 5,
        pressed: false,
        repeat: false,
        modifiers: 0,
    });
    t += 0.016;
    let fx_exit = engine.step_headless_or_gl(false, t, 1280, 800, 1.0, true, true);
    assert!(!fx_exit.fullscreen, "exiting slideshow must clear fullscreen");
    assert!(!fx_exit.in_slideshow, "exiting slideshow must clear inSlideshow");
}

#[test]
fn android_async_open_supports_compare_and_reuse_without_opening_standalone_tabs() {
    let (_guard, files, cache) = temp_env("async_saf");
    let source_path = files.join("compare_source.deckcraft");
    let mut s = deckcraft_engine::Session::with_new();
    s.execute("slide.new", &serde_json::json!({})).unwrap();
    s.execute(
        "file.save",
        &serde_json::json!({"path": source_path.to_string_lossy()}),
    )
    .unwrap();

    let engine = EngineEntry::new(files.to_str().unwrap(), cache.to_str().unwrap(), None);
    engine.execute_command("file.new".to_string());
    let mut t = 0.0;
    let _ = engine.step_headless_or_gl(false, t, 1280, 800, 1.0, true, true);

    engine.execute_command("app.compareDialog".to_string());
    t += 0.016;
    let fx_cmp = engine.step_headless_or_gl(false, t, 1280, 800, 1.0, true, true);
    let cmp_req = fx_cmp
        .picker_request
        .expect("app.compareDialog must emit SAF open_file picker request on Android");
    assert_eq!(cmp_req.mode, "open_file");
    assert!(cmp_req.extensions.contains(&"pptx".to_string()));
    assert!(cmp_req.extensions.contains(&"deckcraft".to_string()));

    engine.deliver_picked_files(
        cmp_req.id as u64,
        vec![source_path.to_string_lossy().into_owned()],
    );
    t += 0.016;
    let _ = engine.step_headless_or_gl(false, t, 1280, 800, 1.0, true, true);
    THREAD_CORES.with(|cell| {
        let map = cell.borrow();
        let core = map.get(&engine.handle()).unwrap();
        assert_eq!(
            core.app.session.documents().len(),
            1,
            "Compare SAF pick must run comparison rather than opening the file as a standalone tab"
        );
    });

    engine.execute_command("app.browseThemeDialog".to_string());
    t += 0.016;
    let fx_theme = engine.step_headless_or_gl(false, t, 1280, 800, 1.0, true, true);
    let theme_req = fx_theme
        .picker_request
        .expect("app.browseThemeDialog must emit SAF open_file picker request");
    assert_eq!(theme_req.mode, "open_file");
}
