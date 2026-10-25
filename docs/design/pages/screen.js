
    const notes = {
      first: "第一次连接。密码页点「连接相机」，弹出「相机 Wi-Fi 已开启？」。取消留在这一页。确认进入正在连接。",
      "first-wait": "连接中返回。正在连接某一台热点，下面是已找到的热点。返回回到第一次连接，停掉这次加入，后台扫描继续。",
      "second-enter": "第二次连接。没有密码框。上次连接 NIKON_Z_30_12345，本次将自动连接到这一台。不想自动连就点返回，回到首页。",
      allow: "等系统确认。画面上是转圈和「等系统确认」。系统窗口仍是系统自己的。不在画面里写长说明。",
      miss: "找不到相机。画面上是「扫描一次」、「请打开相机 Wi-Fi」、「请确认相机 Wi-Fi 已打开」，按钮是「再扫一次」。",
      badpass: "密码错了。锁图标、密码框「请输入相机上的密码」、按钮「重试」。",
      error: "找不到相机。和「找不到相机」同一套短句。",
      daily: "一进来先取最近 100 张，顶部写着已显示几张。往下滑，到底部转圈时再取下一批，不会一次把卡里的全拉下来。先点选择，再勾照片，「同步到相册」才出现。右上角「下载」进入这次的记录。大图可以换一屏张数，列表点缩略图也能选。",
      downloads: "这次保存了哪些。点一行看大图。已经存进相册的是完整颜色，没下完的变淡。可以从相册删掉这一张。",
      style: "盖在照片上。先选一类，再点一条看全部数字。拨盘拨到 AUTO 时，「用这个拍」不会记成使用中。拨回 P 才会。数字在拍照风格文档里，这一版还不能写进相机。",
      frame: "还没做。不用连相机。从相册选一张，下面接一条底栏，另存，原片不动。",
      settings: "还没做。自动同步、地点、忘记相机。现在没有这一页。",
    };
    const seed = [
      { name: "DSC_1042.JPG", size: "8.4 MB", time: "14:21", color: "#6e6256", pair: true },
      { name: "DSC_1043.JPG", size: "7.9 MB", time: "14:22", color: "#8d7358", pair: false },
      { name: "DSC_1044.JPG", size: "9.1 MB", time: "14:24", color: "#3e4650", pair: false },
      { name: "DSC_1045.JPG", size: "6.8 MB", time: "14:31", color: "#a08a72", pair: true },
      { name: "DSC_1046.JPG", size: "11.2 MB", time: "14:36", color: "#5c534c", pair: false },
      { name: "DSC_1047.JPG", size: "8.0 MB", time: "15:02", color: "#7a8490", pair: false },
      { name: "DSC_1048.JPG", size: "7.4 MB", time: "15:18", color: "#4a4038", pair: false },
      { name: "DSC_1049.NEF", size: "24.6 MB", time: "15:18", color: "#9a7f68", pair: false },
      { name: "DSC_1050.JPG", size: "8.8 MB", time: "16:05", color: "#2f3338", pair: false }
    ];
    // 分批加载：先进来取最近 100 张，滑到底再每次取 24 张。
    const FIRST_BATCH = 100;
    const NEXT_BATCH = 24;
    const TOTAL_PHOTOS = 4381;
    const palette = ["#6e6256", "#8d7358", "#3e4650", "#a08a72", "#5c534c", "#7a8490", "#4a4038", "#9a7f68", "#2f3338"];
    let nextPhotoId = seed.length + 1;
    let loadingMore = false;
    let allLoaded = false;
    function makePhotos(count) {
      const out = [];
      for (let i = 0; i < count; i += 1) {
        const n = nextPhotoId;
        nextPhotoId += 1;
        const raw = n % 7 === 3;
        out.push({
          name: "DSC_" + (1100 + n) + (raw ? ".NEF" : ".JPG"),
          size: (raw ? 18 + (n % 9) : 6 + (n % 6)) + "." + (n % 10) + " MB",
          time: String(9 + Math.floor((n % 54) / 6)).padStart(2, "0") + ":" + String((n * 7) % 60).padStart(2, "0"),
          color: palette[n % palette.length],
          pair: n % 5 === 0,
          id: n
        });
      }
      return out;
    }
    let photos = seed.map((item, index) => ({ ...item, id: index + 1 }));
    photos = photos.concat(makePhotos(FIRST_BATCH - photos.length));
    const layouts = {
      4: { cols: 2, rows: 2 },
      6: { cols: 2, rows: 3 },
      9: { cols: 3, rows: 3 }
    };
    let view = "grid";
    let perScreen = 9;
    let picking = false;
    const picked = new Set();
    let previewAt = -1;
    let pendingIds = [];
    let session = [];
    let saved = 0;
    let current = 0;
    let fraction = 0;
    let running = false;
    let syncTimer = null;
    let dlAt = -1;

    const photoGrid = document.getElementById("photoGrid");
    const photoList = document.getElementById("photoList");
    const actionBar = document.getElementById("actionBar");
    const preview = document.getElementById("preview");
    const confirm = document.getElementById("confirm");

    function sizeTiles() {
      const gallery = document.getElementById("gallery");
      if (!gallery || !photoGrid) return;
      const layout = layouts[perScreen];
      const tile = Math.floor((gallery.clientHeight - (layout.rows - 1) * 2) / layout.rows);
      if (tile > 0) photoGrid.style.setProperty("--tile-h", tile + "px");
    }

    function paintLoadLine() {
      const line = document.getElementById("loadLine");
      if (!line) return;
      line.textContent = allLoaded
        ? "已显示全部 " + photos.length + " 张"
        : "正在加载照片，已显示 " + photos.length + " 张";
    }

    function loadMore() {
      if (loadingMore || allLoaded) return;
      const box = document.getElementById("loadMore");
      const text = document.getElementById("loadMoreText");
      loadingMore = true;
      if (box) box.classList.remove("end");
      if (text) text.textContent = "正在加载下一批";
      setTimeout(() => {
        const room = TOTAL_PHOTOS - photos.length;
        photos = photos.concat(makePhotos(Math.min(NEXT_BATCH, room)));
        allLoaded = photos.length >= TOTAL_PHOTOS;
        loadingMore = false;
        if (text) text.textContent = allLoaded ? "没有更多照片了" : "正在加载下一批";
        if (box) box.classList.toggle("end", allLoaded);
        renderPhotos();
      }, 520);
    }

    function renderPhotos() {
      if (!photoGrid || !photoList) return;
      const layout = layouts[perScreen];
      photoGrid.hidden = view !== "grid";
      photoList.hidden = view !== "list";
      sizeTiles();
      document.getElementById("countRow").hidden = view !== "grid";
      photoGrid.style.setProperty("--cols", layout.cols);
      photoGrid.style.setProperty("--rows", layout.rows);
      photoGrid.classList.toggle("cols-2", layout.cols === 2);
      photoGrid.classList.toggle("cols-3", layout.cols === 3);
      photoGrid.classList.toggle("rows-2", layout.rows === 2);
      photoGrid.classList.toggle("rows-3", layout.rows === 3);
      photoGrid.classList.toggle("exact", photos.length > 0 && photos.length <= perScreen);
      actionBar.hidden = !picking;
      document.getElementById("pickedLabel").textContent = "已选 " + picked.size + " 张";
      document.getElementById("selectToggle").textContent = picking ? "取消" : "选择";
      document.getElementById("batchSync").hidden = !picking || picked.size === 0;
      document.getElementById("openDownloads").textContent = session.length ? "下载 · 本次 " + saved + " 张" : "下载";
      const tile = (photo) => {
        const on = picked.has(photo.id) ? " sel" : "";
        const mark = picking ? "<i class=\"mark\"></i>" : "";
        return { on, mark };
      };
      photoGrid.innerHTML = photos.map((photo) => {
        const bits = tile(photo);
        return "<button type=\"button\" class=\"tile" + bits.on + "\" data-id=\"" + photo.id + "\" style=\"background:" + photo.color + "\">" + bits.mark + "<span class=\"size\">" + photo.size + "</span></button>";
      }).join("");
      photoList.innerHTML = photos.map((photo) => {
        const bits = tile(photo);
        return "<button type=\"button\" class=\"rowitem" + (picking ? " picking" : "") + bits.on + "\" data-id=\"" + photo.id + "\"><i class=\"thumb\" style=\"background:" + photo.color + "\"></i><span><b>" + photo.name + "</b><small>" + photo.time + "</small></span>" + (picking ? bits.mark : "<span class=\"bytes\">" + photo.size + "</span>") + "</button>";
      }).join("");
      if (previewAt >= photos.length) previewAt = photos.length - 1;
      if (previewAt >= 0 && photos[previewAt]) showPreview();
      else preview.hidden = true;
      paintLoadLine();
    }

    function showPreview() {
      const photo = photos[previewAt];
      if (!photo) { preview.hidden = true; return; }
      preview.hidden = false;
      document.getElementById("previewShot").style.background = photo.color;
      document.getElementById("previewName").textContent = photo.name;
      document.getElementById("previewIndex").textContent = (previewAt + 1) + " / " + photos.length;
      const pair = photo.pair ? " · 含同一次 NEF" : "";
      document.getElementById("previewMeta").textContent = photo.size + " · " + photo.time + pair + " · 左右滑换张";
    }

    function openPhoto(id) {
      if (picking) {
        if (picked.has(id)) picked.delete(id);
        else picked.add(id);
        if (photoGrid) renderPhotos();
        return;
      }
      previewAt = photos.findIndex((photo) => photo.id === id);
      showPreview();
    }

    function step(delta) {
      if (previewAt < 0 || photos.length === 0) return;
      previewAt = (previewAt + delta + photos.length) % photos.length;
      showPreview();
    }

    function askDelete(ids) {
      pendingIds = ids;
      const names = photos.filter((photo) => ids.includes(photo.id));
      const title = document.getElementById("confirmTitle");
      const body = document.getElementById("confirmBody");
      if (names.length === 1) {
        const photo = names[0];
        title.textContent = "删除这张";
        body.textContent = photo.pair
          ? photo.name + " 和同一次快门的 NEF 会从相机删掉。已经同步到手机相册的也会删掉。不能恢复。"
          : photo.name + " 会从相机删掉。已经同步到手机相册的也会删掉。不能恢复。";
      } else {
        title.textContent = "删除这 " + names.length + " 张";
        body.textContent = "这些照片会从相机删掉。已经同步到手机相册的也会一起删掉。同一次快门的 JPG 和 NEF 一起删。不能恢复。";
      }
      confirm.hidden = false;
    }

    (document.getElementById("countSeg") || { addEventListener() {} }).addEventListener("click", (event) => {
      const button = event.target.closest("button");
      if (!button) return;
      perScreen = Number(button.dataset.count);
      document.querySelectorAll("#countSeg button").forEach((item) => item.classList.toggle("on", item === button));
      if (photoGrid) renderPhotos();
    });
    (document.getElementById("viewSeg") || { addEventListener() {} }).addEventListener("click", (event) => {
      const button = event.target.closest("button");
      if (!button) return;
      view = button.dataset.view;
      document.querySelectorAll("#viewSeg button").forEach((item) => item.classList.toggle("on", item === button));
      if (photoGrid) renderPhotos();
    });
    (document.getElementById("selectToggle") || { addEventListener() {} }).addEventListener("click", () => {
      picking = !picking;
      if (!picking) picked.clear();
      preview.hidden = true;
      previewAt = -1;
      if (photoGrid) renderPhotos();
    });
    if (photoGrid) photoGrid.addEventListener("click", (event) => {
      const button = event.target.closest("[data-id]");
      if (button) openPhoto(Number(button.dataset.id));
    });
    if (photoList) photoList.addEventListener("click", (event) => {
      const button = event.target.closest("[data-id]");
      if (button) openPhoto(Number(button.dataset.id));
    });
    (document.getElementById("closePreview") || { addEventListener() {} }).addEventListener("click", () => {
      preview.hidden = true;
      previewAt = -1;
    });
    (document.getElementById("prevPhoto") || { addEventListener() {} }).addEventListener("click", () => step(-1));
    (document.getElementById("nextPhoto") || { addEventListener() {} }).addEventListener("click", () => step(1));
    (document.getElementById("previewDelete") || { addEventListener() {} }).addEventListener("click", () => {
      const photo = photos[previewAt];
      if (photo) askDelete([photo.id]);
    });
    (document.getElementById("batchDelete") || { addEventListener() {} }).addEventListener("click", () => {
      if (picked.size) askDelete([...picked]);
    });
    (document.getElementById("confirmCancel") || { addEventListener() {} }).addEventListener("click", () => { confirm.hidden = true; });
    (document.getElementById("confirmOk") || { addEventListener() {} }).addEventListener("click", () => {
      photos = photos.filter((photo) => !pendingIds.includes(photo.id));
      pendingIds.forEach((id) => picked.delete(id));
      confirm.hidden = true;
      if (photos.length === 0) { preview.hidden = true; previewAt = -1; }
      if (photoGrid) renderPhotos();
    });
    const stage = document.getElementById("previewStage");
    let startX = 0;
    if (stage) stage.addEventListener("pointerdown", (event) => { startX = event.clientX; });
    if (stage) stage.addEventListener("pointerup", (event) => {
      if (event.target.closest(".edge")) return;
      const dx = event.clientX - startX;
      if (dx > 48) step(-1);
      else if (dx < -48) step(1);
    });
    if (photoGrid) renderPhotos();
    const galleryEl = document.getElementById("gallery");
    const loadMoreEl = document.getElementById("loadMore");
    if (galleryEl && loadMoreEl && window.IntersectionObserver) {
      // 哨兵进到视口前 100px 就取下一批，滑动时不会停住等
      new IntersectionObserver((entries) => {
        if (entries.some((entry) => entry.isIntersecting)) loadMore();
      }, { root: galleryEl, rootMargin: "100px", threshold: 0 }).observe(loadMoreEl);
    }
    window.addEventListener("resize", () => {
      if (photoGrid) renderPhotos();
    });
    function go(id) {
      const routes = {
        first: "01a-home.html",
        "first-wait": "01b-connecting-back.html",
        "second-enter": "02a-home.html",
        "second-back": "02b-connecting-back.html",
        miss: "03-cant-find-camera.html",
        badpass: "04-password-error.html",
        allow: "05-waiting-for-system-confirmation.html",
        daily: "06-photos.html",
        downloads: "07-download-progress.html",
        style: "08-style.html",
        frame: "10-frame.html",
        settings: "09-settings.html",
        error: "11-hotspot-off.html"
      };
      if (routes[id]) location.href = routes[id];
    }

    function paintProgress() {
      const card = document.getElementById("progress");
      if (!card) return;
      const total = session.length;
      card.hidden = total === 0;
      if (!total) return;
      const index = running ? current : Math.max(saved - 1, 0);
      const photo = session[index];
      document.getElementById("progressIndex").textContent = (index + 1) + "/" + total;
      document.getElementById("progressName").textContent = photo ? photo.name : "";
      const pct = running ? Math.round(fraction * 100) : 100;
      document.getElementById("progressPct").textContent = pct + "%";
      document.getElementById("progressFill").style.width = pct + "%";
      document.getElementById("progressSaved").textContent = "已保存 " + saved + " 张";
      document.getElementById("progressLeft").textContent = "还剩 " + (total - saved) + " 张";
    }

    function outcome(index) {
      if (index < saved) return "已存进相册";
      if (running && index === current) return "还没下完";
      return "还没开始";
    }

    function renderDownloads() {
      if (!document.getElementById("sessionList")) return;
      const total = session.length;
      document.getElementById("sessionLine").textContent = running
        ? "正在保存 " + (current + 1) + "/" + total + " · " + session[current].name
        : (total ? "这一次已经结束，" + saved + "/" + total : "还没开始。先在照片里勾选，再点同步到相册。");
      document.getElementById("sessionList").innerHTML = session.map((photo, index) => {
        return "<button type=\"button\" class=\"dl-row\" data-at=\"" + index + "\"><i style=\"background:" + photo.color + "\"></i><b>" + photo.name + "</b><span>" + outcome(index) + "</span></button>";
      }).join("");
    }

    function showDl() {
      const photo = session[dlAt];
      const box = document.getElementById("dlPreview");
      if (!photo) { box.hidden = true; return; }
      box.hidden = false;
      const shot = document.getElementById("dlShot");
      shot.style.background = photo.color;
      shot.classList.toggle("faded", outcome(dlAt) !== "已存进相册");
      document.getElementById("dlName").textContent = photo.name;
      document.getElementById("dlOutcome").textContent = outcome(dlAt);
      document.getElementById("dlState").textContent = (dlAt + 1) + "/" + session.length;
    }

    function startSync() {
      session = photos.filter((photo) => picked.has(photo.id));
      if (!session.length || running) return;
      picking = false;
      picked.clear();
      saved = 0;
      current = 0;
      fraction = 0;
      running = true;
      if (syncTimer) clearInterval(syncTimer);
      syncTimer = setInterval(() => {
        fraction += 0.12;
        if (fraction >= 1) {
          saved += 1;
          fraction = 0;
          if (saved >= session.length) {
            running = false;
            clearInterval(syncTimer);
            syncTimer = null;
          } else {
            current = saved;
          }
        }
        paintProgress();
        if (photoGrid) renderPhotos();
        const downloadsScreen = document.getElementById("downloads");
        const dlPreviewEl = document.getElementById("dlPreview");
        if (downloadsScreen && !downloadsScreen.classList.contains("on") && dlPreviewEl && dlPreviewEl.hidden === false) showDl();
        if (downloadsScreen && downloadsScreen.classList.contains("on")) renderDownloads();
      }, 140);
      paintProgress();
      if (photoGrid) renderPhotos();
    }

    document.body.addEventListener("click", (event) => {
      const target = event.target.closest("[data-go]");
      if (target) go(target.dataset.go);
    });
    (document.getElementById("firstConnect") || { addEventListener() {} }).addEventListener("click", () => {
      document.getElementById("wifiConfirm").hidden = false;
    });
    (document.getElementById("wifiCancel") || { addEventListener() {} }).addEventListener("click", () => {
      document.getElementById("wifiConfirm").hidden = true;
    });
    (document.getElementById("wifiOk") || { addEventListener() {} }).addEventListener("click", () => {
      document.getElementById("wifiConfirm").hidden = true;
    });
    const missCounts = ["扫描一次", "扫描两次", "扫描三次", "扫描四次"];
    let missN = 0;
    (document.getElementById("missRetry") || { addEventListener() {} }).addEventListener("click", () => {
      missN = Math.min(missN + 1, missCounts.length - 1);
      document.getElementById("missCount").textContent = missCounts[missN];
      document.getElementById("missHotspot").hidden = missN < 2;
    });
    (document.getElementById("firstEye") || { addEventListener() {} }).addEventListener("click", () => {
      const input = document.getElementById("firstPass");
      const show = input.type === "password";
      input.type = show ? "text" : "password";
      const eye = document.getElementById("firstEye");
      eye.classList.toggle("on", show);
      eye.setAttribute("aria-label", show ? "隐藏密码" : "显示密码");
    });
    (document.getElementById("openDownloads") || { addEventListener() {} }).addEventListener("click", () => go("downloads"));
    (document.getElementById("batchSync") || { addEventListener() {} }).addEventListener("click", startSync);
    (document.getElementById("sessionList") || { addEventListener() {} }).addEventListener("click", (event) => {
      const row = event.target.closest("[data-at]");
      if (!row) return;
      dlAt = Number(row.dataset.at);
      showDl();
    });
    (document.getElementById("dlBack") || { addEventListener() {} }).addEventListener("click", () => {
      document.getElementById("dlPreview").hidden = true;
      dlAt = -1;
    });
    (document.getElementById("dlDelete") || { addEventListener() {} }).addEventListener("click", () => {
      if (dlAt >= 0) document.getElementById("dlConfirm").hidden = false;
    });
    (document.getElementById("dlConfirmCancel") || { addEventListener() {} }).addEventListener("click", () => {
      document.getElementById("dlConfirm").hidden = true;
    });
    (document.getElementById("dlConfirmOk") || { addEventListener() {} }).addEventListener("click", () => {
      if (dlAt < 0) return;
      if (dlAt < saved) saved -= 1;
      session.splice(dlAt, 1);
      if (current >= session.length) current = Math.max(session.length - 1, 0);
      if (!session.length) { running = false; if (syncTimer) clearInterval(syncTimer); }
      document.getElementById("dlConfirm").hidden = true;
      document.getElementById("dlPreview").hidden = true;
      dlAt = -1;
      paintProgress();
      if (photoGrid) renderPhotos();
      renderDownloads();
    });
    const recipes = [
      { id: "fujichrome", use: "person", name: "Fujichrome", line: "白天人像。肤色柔，颜色仍亮。", base: "Somber（阴郁）", effect: "90", quick: "−2.00", sharp: "0.00", mid: "0.00", clarity: "−3.00", contrast: "−3.00", sat: "+1.00", wb: "直射阳光", shift: "琥珀 2.00、品红 1.25", adl: "超高", nr: "低" },
      { id: "nostalgic", use: "person", name: "Nostalgic Print", line: "旧相纸。锐化往低，静物也能用。", base: "Dream（梦幻）", effect: "90", quick: "0.00", sharp: "−0.50", mid: "−2.00", clarity: "−4.00", contrast: "+3.00", sat: "−2.00", wb: "自然光自动", shift: "蓝 2.00、绿 0.50", adl: "标准", nr: "低" },
      { id: "silver", use: "scene", name: "Silver Slide", line: "白天风景。正片，饱和高。", base: "Dramatic（戏剧）", effect: "70", quick: "0.00", sharp: "+2.00", mid: "+1.00", clarity: "+1.00", contrast: "−1.00", sat: "+3.00", wb: "直射阳光", shift: "琥珀 3.00、品红 1.00", adl: "高", nr: "低" },
      { id: "classic", use: "scene", name: "Classic Slide", line: "白天风景。正片，颜色收一点。", base: "Pop（流行）", effect: "30", quick: "0.00", sharp: "0.00", mid: "+1.00", clarity: "+2.00", contrast: "+1.00", sat: "−2.00", wb: "5200K", shift: "品红 0.75", adl: "标准", nr: "低" },
      { id: "weekend", use: "scene", name: "Weekend Negative", line: "白天外出。负片，绿一点。", base: "Sunday（星期日）", effect: "60", quick: "0.00", sharp: "−2.00", mid: "0.00", clarity: "+3.00", contrast: "+1.00", sat: "−3.00", wb: "6500K", shift: "绿 2.00", adl: "标准", nr: "低" },
      { id: "cinemacolor", use: "cine", name: "Cinema Color", line: "电影感，要质感。漂白。", base: "Bleached（漂白）", effect: "60", quick: "0.00", sharp: "+2.00", mid: "+1.00", clarity: "−3.00", contrast: "−1.00", sat: "+3.00", wb: "直射阳光", shift: "琥珀 5.00、品红 1.00", adl: "高", nr: "低" },
      { id: "cinefilm", use: "cine", name: "CineFilm", line: "电影感。饱和低，阴影还在。", base: "Silence（寂静）", effect: "70", quick: "0.00", sharp: "+1.00", mid: "+2.00", clarity: "−2.00", contrast: "+1.00", sat: "+3.00", wb: "自动（0），保持白色", shift: "琥珀 5.00、品红 0.50", adl: "自动", nr: "低" },
      { id: "vintage", use: "cine", name: "Vintage Color", line: "有年代的颜色，质感偏软。", base: "Melancholic（忧郁）", effect: "90", quick: "−1.00", sharp: "+1.00", mid: "0.00", clarity: "−2.00", contrast: "+1.00", sat: "−1.00", wb: "日光色荧光灯", shift: "琥珀 3.00、绿 2.25", adl: "标准", nr: "低" },
      { id: "analog", use: "cine", name: "Analog Film", line: "负片质感。锐化低。", base: "Pure（纯粹）", effect: "80", quick: "0.00", sharp: "−1.00", mid: "0.00", clarity: "+2.00", contrast: "+1.00", sat: "+3.00", wb: "自动（1），保持整体氛围", shift: "琥珀 3.00、绿 0.50", adl: "低", nr: "低" },
      { id: "aged", use: "cine", name: "Aged Analog", line: "旧相纸的质感，颜色淡一点。", base: "Denim（丹宁）", effect: "80", quick: "0.00", sharp: "+2.00", mid: "+1.00", clarity: "−2.00", contrast: "+1.00", sat: "−2.00", wb: "直射阳光", shift: "琥珀 4.00、绿 0.50", adl: "低", nr: "低" },
      { id: "tungsten", use: "all", name: "Tungsten Dawn", line: "暖光和混合光。不作为白天默认。", base: "Morning（早晨）", effect: "70", quick: "0.00", sharp: "−2.00", mid: "+2.00", clarity: "−2.00", contrast: "+1.00", sat: "+2.00", wb: "昼白色荧光灯", shift: "琥珀 2.00、品红 1.00", adl: "高", nr: "低" }
    ];
    const styleUses = [
      { id: "person", name: "白天拍人" },
      { id: "scene", name: "白天拍景" },
      { id: "cine", name: "电影感" },
      { id: "all", name: "全部" }
    ];
    let styleUse = "person";
    let styleId = "fujichrome";
    let styleArmedId = "";
    let styleDial = "P";
    const styleFields = [
      ["优化校准", "base"],
      ["效果强弱", "effect"],
      ["快速锐化", "quick"],
      ["锐化", "sharp"],
      ["中间调锐化", "mid"],
      ["清晰度", "clarity"],
      ["对比", "contrast"],
      ["饱和度", "sat"],
      ["白平衡", "wb"],
      ["白平衡偏移", "shift"],
      ["动态 D-Lighting", "adl"],
      ["高感光度降噪", "nr"],
      ["ISO", "iso"]
    ];
    function currentRecipe() {
      return recipes.find((item) => item.id === styleId) || recipes[0];
    }
    function renderStyles() {
      const armed = recipes.find((item) => item.id === styleArmedId);
      const openStyleEl = document.getElementById("openStyle");
      if (openStyleEl) openStyleEl.textContent = armed ? "风格 · " + armed.name : "风格";
      if (!document.getElementById("styleUses")) return;
      document.getElementById("styleUses").innerHTML = styleUses.map((item) => {
        return "<button type=\"button\" data-use=\"" + item.id + "\"" + (item.id === styleUse ? " class=\"on\"" : "") + ">" + item.name + "</button>";
      }).join("");
      document.querySelectorAll("#styleDial button").forEach((button) => {
        button.classList.toggle("on", button.dataset.dial === styleDial);
      });
      const shown = recipes.filter((item) => styleUse === "all" || item.use === styleUse);
      document.getElementById("styleList").innerHTML = shown.map((item) => {
        const mark = item.id === styleArmedId ? "<em>使用中</em>" : "<em></em>";
        return "<button type=\"button\" class=\"style-item\" data-recipe=\"" + item.id + "\"><span><b>" + item.name + "</b><small>" + item.line + "</small></span>" + mark + "</button>";
      }).join("");
      document.getElementById("styleHint").textContent = "出处：Fuji X Weekly，2022。适用于 Z30。拨盘停在 AUTO 时，用这个拍不会记成使用中。";
      const recipe = currentRecipe();
      document.getElementById("stylePageName").textContent = recipe.name;
      document.getElementById("stylePageMark").textContent = recipe.id === styleArmedId ? "使用中" : "";
      const rows = styleFields.map((field) => {
        const value = field[1] === "iso" ? "不超过 3200，只提示" : recipe[field[1]];
        return "<div class=\"row\"><span>" + field[0] + "</span><span>" + value + "</span></div>";
      }).join("");
      document.getElementById("stylePageBody").innerHTML = "<p class=\"sub\">" + recipe.line + "</p>" + rows + "<p class=\"hint\">Fuji X Weekly，Ritchie Roesch，2022-07-03。适用于 Z30、Z50、Zfc。只影响之后的 JPEG。</p>";
      const blocked = styleDial === "AUTO";
      document.getElementById("styleApplyHint").textContent = blocked
        ? "拨盘在 AUTO，点下去不会记成使用中。"
        : (recipe.id === styleArmedId
          ? "已记成当前这套。这一版还不能写进 Z30，按这些数字在拍摄菜单里设一次。"
          : "拨盘在 P。点下去只在这页记成使用中，还不会写进相机。");
    }
    (document.getElementById("styleUses") || { addEventListener() {} }).addEventListener("click", (event) => {
      const button = event.target.closest("[data-use]");
      if (!button) return;
      styleUse = button.dataset.use;
      if (document.getElementById('styleUses')) renderStyles();
    });
    (document.getElementById("styleDial") || { addEventListener() {} }).addEventListener("click", (event) => {
      const button = event.target.closest("[data-dial]");
      if (!button) return;
      styleDial = button.dataset.dial;
      if (document.getElementById('styleUses')) renderStyles();
    });
    (document.getElementById("styleList") || { addEventListener() {} }).addEventListener("click", (event) => {
      const button = event.target.closest("[data-recipe]");
      if (!button) return;
      styleId = button.dataset.recipe;
      document.getElementById("stylePage").hidden = false;
      if (document.getElementById('styleUses')) renderStyles();
    });
    (document.getElementById("styleBack") || { addEventListener() {} }).addEventListener("click", () => {
      document.getElementById("stylePage").hidden = true;
    });
    (document.getElementById("styleApply") || { addEventListener() {} }).addEventListener("click", () => {
      if (styleDial === "AUTO") {
        document.getElementById("styleApplyHint").textContent = "拨盘在 AUTO，没有写入。拨到 P 或 U1 再点。";
        return;
      }
      styleArmedId = styleId;
      if (document.getElementById('styleUses')) renderStyles();
    });
    if (document.getElementById('styleUses')) renderStyles();
    document.querySelectorAll("#settings .row").forEach((row) => {
      row.style.cursor = "pointer";
      row.addEventListener("click", () => {
        const value = row.lastElementChild;
        if (value.textContent === "忘记") return;
        value.textContent = value.textContent === "开" ? "关" : "开";
      });
    });

    const buttons = document.querySelectorAll("#nav button[data-id]");
    buttons.forEach((button) => {
      button.addEventListener("click", () => go(button.dataset.id));
    });
    document.querySelectorAll("#nav .chapter-toggle").forEach((button) => {
      button.addEventListener("click", () => {
        button.closest(".chapter").classList.toggle("open");
      });
    });

    const album = [
      { id: 1, scene: "forest", model: "NIKON Z 30", params: "35mm  f/6.3  1/200s  ISO 250", meta: "2026.10.21    30.27°N  120.15°E" },
      { id: 2, scene: "sky", model: "", params: "", meta: "" }
    ];
    const tones = [
      { id: "light", name: "白底" },
      { id: "dark", name: "黑底" }
    ];
    let frameId = 1;
    const frameFlags = { model: true, params: true, date: true };
    let frameTone = "light";

    function framePhoto() {
      return album.find((item) => item.id === frameId) || album[0];
    }

    function frameMarkup(tone, photo) {
      const model = photo.model || "未知机型";
      const params = photo.params || "参数未记录";
      const meta = photo.meta || "日期未记录";
      const left = frameFlags.model ? "<span class=\"kicker\">SHOT ON</span><b class=\"name\">" + model + "</b>" : "";
      const right = (frameFlags.params ? "<span>" + params + "</span>" : "") + (frameFlags.date ? "<small>" + meta + "</small>" : "");
      return "<div class=\"fm " + tone + "\"><div class=\"scene " + photo.scene + "\"></div><div class=\"footer\"><div>" + left + "</div><div class=\"mid\"><i></i><b class=\"dot\"></b><i></i></div><div class=\"end\">" + right + "</div></div></div>";
    }

    function renderFrames() {
      const photo = framePhoto();
      document.getElementById("framePick").innerHTML = album.map((item) => {
        return "<button type=\"button\" class=\"scene " + item.scene + (item.id === frameId ? " on" : "") + "\" data-id=\"" + item.id + "\"></button>";
      }).join("");
      document.getElementById("frameMount").innerHTML = frameMarkup(frameTone, photo);
      document.getElementById("frameStyles").innerHTML = tones.map((item) => {
        return "<button type=\"button\" data-style=\"" + item.id + "\"" + (item.id === frameTone ? " class=\"on\"" : "") + ">" + item.name + "</button>";
      }).join("");
      document.getElementById("wallGrid").innerHTML = tones.map((item) => {
        return "<button type=\"button\" class=\"card" + (item.id === frameTone ? " on" : "") + "\" data-style=\"" + item.id + "\">" + frameMarkup(item.id, photo) + "<span>" + item.name + "</span></button>";
      }).join("");
      document.getElementById("frameHint").textContent = photo.model
        ? "拍摄信息来自这张照片。另存后原片还在，新图按原来的拍摄时间排在旁边。"
        : "这张没有拍摄信息，底栏用兜底文案。可以改字。原片不动。";
    }

    (document.getElementById("framePick") || { addEventListener() {} }).addEventListener("click", (event) => {
      const button = event.target.closest("button");
      if (!button) return;
      frameId = Number(button.dataset.id);
      if (document.getElementById('framePick')) renderFrames();
    });
    (document.getElementById("frameStyles") || { addEventListener() {} }).addEventListener("click", (event) => {
      const button = event.target.closest("button");
      if (!button) return;
      frameTone = button.dataset.style;
      if (document.getElementById('framePick')) renderFrames();
    });
    (document.getElementById("wallGrid") || { addEventListener() {} }).addEventListener("click", (event) => {
      const button = event.target.closest(".card");
      if (!button) return;
      frameTone = button.dataset.style;
      if (document.getElementById('framePick')) renderFrames();
    });
    document.querySelectorAll("#frame [data-flag]").forEach((button) => {
      button.addEventListener("click", () => {
        frameFlags[button.dataset.flag] = !frameFlags[button.dataset.flag];
        button.textContent = frameFlags[button.dataset.flag] ? "开" : "关";
        if (document.getElementById('framePick')) renderFrames();
      });
    });
    (document.getElementById("frameSave") || { addEventListener() {} }).addEventListener("click", () => {
      document.getElementById("frameHint").textContent = "已另存 1 张到相册。原来的照片没有改。";
    });
    if (document.getElementById('framePick')) renderFrames();
  
    if (document.getElementById('sessionList')) renderDownloads();
