# 尼康 Z30 相册无限滚动与懒加载方案 (Progressive Gallery Loading Mechanism)

## 1. 方案背景与目标
针对尼康 Z30 相机 SD 卡中动辄几千到上万张照片的场景，如果首次连接时全量拉取全部缩略图，会导致 APP 长时间无响应或假死。
本方案采用**“首次极速加载 + 滚动无限懒加载”**的策略，在保证用户体验丝滑无感的同时，极大地降低相机通信压力。

## 2. 核心加载策略
1. **首次极速秒开**：用户刚进入相册页面时，默认仅向相机请求最近的 **100 张** 照片（或更少，如一屏的数量）。此过程耗时极短，可瞬间完成渲染。
2. **哨兵触发机制 (Sentinel Trigger)**：在图片列表的最底部放置一个 Loading 动画（即“哨兵”元素）。通过浏览器原生的 `IntersectionObserver` 监听该元素。
3. **滚动提前加载**：设置 `rootMargin: '100px'`。当用户往下滑动，距离底部“哨兵”还有 100 像素时，系统自动在后台静默发起下一批（如 20~30 张）的请求，并追加到页面底部。
4. **状态防抖锁**：引入 `isLoading` 状态锁，防止用户滑动过快时触发重复的网络请求。

## 3. 界面重构与交互细节
* **隐藏默认进度条**：首次进入页面时，隐藏代表下载进度的双横线，保持页面清爽。只有在发生真实下载任务时才显示进度。
* **实时计数反馈**：在页面顶部显示“正在加载照片，已显示 X 张”，该数字随着懒加载的触发实时动态增加，给予用户明确的掌控感。

## 4. 前端实现代码 (HTML/CSS/JS)

以下前端代码已落到原型 `docs/design/pages/06-photos.html`，样式在 `screen.css` 的 `.gallery`、`.loadline`、`.loadmore` 三段，逻辑在 `screen.js` 的 `loadMore`、`sizeTiles`、`paintLoadLine`。原型用的是应用现有暗色稿，不是下面这份独立演示的配色。

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>相册懒加载演示</title>
    <style>
        /* 基础重置与深色模式样式 */
        body { margin: 0; padding: 0; background-color: #1c1c1e; color: #f2f2f7; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; -webkit-font-smoothing: antialiased; }
        .header { position: sticky; top: 0; background-color: rgba(28, 28, 30, 0.95); backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); padding: 16px 20px 10px; z-index: 100; }
        .top-bar { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 8px; }
        .title { font-size: 28px; font-weight: bold; margin: 0; }
        .status { font-size: 13px; color: #8e8e93; display: flex; flex-direction: column; align-items: flex-end; }
        .status .conn-status { color: #8e8e93; margin-bottom: 4px; }
        
        /* 进度条区域 - 默认隐藏 */
        .progress-section { display: none; margin-top: 12px; }
        .progress-section.active { display: block; }
        .progress-bars { display: flex; gap: 8px; margin-top: 8px; }
        .bar-bg { flex: 1; height: 4px; background-color: #3a3a3c; border-radius: 2px; overflow: hidden; }
        .bar-fill { height: 100%; background-color: #ffffff; border-radius: 2px; }
        .bar-fill.partial { width: 45%; }
        .bar-fill.empty { width: 0%; }
        
        /* 标签与控制栏 */
        .controls { padding: 0 20px; margin-top: 12px; }
        .filter-row { display: flex; gap: 10px; overflow-x: auto; padding-bottom: 12px; scrollbar-width: none; }
        .filter-row::-webkit-scrollbar { display: none; }
        .pill { background-color: #2c2c2e; color: #ffffff; padding: 8px 16px; border-radius: 20px; font-size: 14px; white-space: nowrap; font-weight: 500; }
        .pill.active { background-color: #4a4a4c; }
        .pill.group { background-color: #2c2c2e; padding: 4px; border-radius: 20px; display: flex; }
        .pill.group span { padding: 4px 12px; border-radius: 16px; }
        .pill.group span.active { background-color: #4a4a4c; }
        
        /* 图片网格区 */
        .gallery-section { padding: 0 20px 20px; }
        .date-header { font-size: 18px; font-weight: bold; margin: 20px 0 12px; }
        .grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
        .photo-card { position: relative; aspect-ratio: 1 / 1; background-color: #2c2c2e; border-radius: 12px; overflow: hidden; display: flex; align-items: center; justify-content: center; font-size: 12px; color: #8e8e93; }
        .photo-card img { width: 100%; height: 100%; object-fit: cover; }
        .size-badge { position: absolute; bottom: 8px; left: 8px; font-size: 11px; font-weight: 600; text-shadow: 0 1px 2px rgba(0,0,0,0.8); }
        
        /* 懒加载哨兵元素与加载动画 */
        .loading-sentinel { padding: 30px 0 50px; display: flex; justify-content: center; align-items: center; }
        .spinner { width: 24px; height: 24px; border: 3px solid rgba(255,255,255,0.1); border-radius: 50%; border-top-color: #8e8e93; animation: spin 1s ease-in-out infinite; }
        @keyframes spin { to { transform: rotate(360deg); } }
        
        /* 悬浮测试按钮 */
        .test-btn { position: fixed; bottom: 30px; right: 20px; background-color: #0a84ff; color: white; border: none; padding: 12px 20px; border-radius: 24px; font-size: 14px; font-weight: bold; box-shadow: 0 4px 12px rgba(0,0,0,0.5); z-index: 1000; }
    </style>
</head>
<body>

    <!-- 顶部状态栏 -->
    <div class="header">
        <div class="top-bar">
            <h1 class="title">照片</h1>
            <div class="status">
                <span class="conn-status">已连接 · 外网中断</span>
                <!-- 动态显示的当前加载张数 -->
                <span>正在加载照片，已显示 <span id="display-count">4</span> 张</span>
            </div>
        </div>
        
        <!-- 进度条区域 (默认隐藏) -->
        <div class="progress-section" id="progressArea">
            <div style="font-size: 13px; text-align: right; color: #8e8e93; margin-bottom: 4px;">下载 · 本次 2 张</div>
            <div class="progress-bars">
                <div class="bar-bg"><div class="bar-fill partial"></div></div>
                <div class="bar-bg"><div class="bar-fill empty"></div></div>
            </div>
        </div>
    </div>

    <!-- 控制面板 -->
    <div class="controls">
        <div class="filter-row">
            <div class="pill group">
                <span class="active">大图</span>
                <span>列表</span>
            </div>
            <div class="pill">最新</div>
            <div class="pill">选择</div>
        </div>
        <div class="filter-row">
            <div class="pill active">全部</div>
            <div class="pill">JPEG</div>
            <div class="pill">RAW / NEF</div>
            <div class="pill">MOV</div>
        </div>
    </div>

    <!-- 照片网格容器 -->
    <div class="gallery-section" id="galleryContainer">
        <!-- 初始数据（示例） -->
        <div class="date-header">今天</div>
        <div class="grid">
            <div class="photo-card">
                <img src="[https://picsum.photos/400/400?random=1](https://picsum.photos/400/400?random=1)" alt="photo">
                <div class="size-badge">7.7 MB</div>
            </div>
            <div class="photo-card">
                <img src="[https://picsum.photos/400/400?random=2](https://picsum.photos/400/400?random=2)" alt="photo">
                <div class="size-badge">8.2 MB</div>
            </div>
        </div>

        <div class="date-header">8月17日</div>
        <div class="grid">
            <div class="photo-card">
                <img src="[https://picsum.photos/400/400?random=3](https://picsum.photos/400/400?random=3)" alt="photo">
                <div class="size-badge">29.7 MB</div>
            </div>
            <div class="photo-card">
                <img src="[https://picsum.photos/400/400?random=4](https://picsum.photos/400/400?random=4)" alt="photo">
                <div class="size-badge">8.1 MB</div>
            </div>
        </div>
    </div>

    <!-- 懒加载哨兵元素 -->
    <div class="loading-sentinel" id="sentinel">
        <div class="spinner"></div>
    </div>

    <!-- 悬浮测试按钮：用于模拟触发下载进度条 -->
    <button class="test-btn" onclick="toggleProgress()">模拟触发下载</button>

    <script>
        // --- 进度条控制逻辑 ---
        function toggleProgress() {
            const area = document.getElementById('progressArea');
            area.classList.toggle('active');
        }

        // --- 无限懒加载逻辑 ---
        let currentCount = 4; // 当前已显示数量
        const totalPhotos = 4381; // 假设总照片数
        let isLoading = false; // 防抖锁

        const displayCountEl = document.getElementById('display-count');
        const galleryContainer = document.getElementById('galleryContainer');
        const sentinel = document.getElementById('sentinel');

        // 模拟向相机请求下一批数据并追加到页面
        function loadMorePhotos() {
            if (isLoading || currentCount >= totalPhotos) return;
            
            isLoading = true;
            
            // 模拟网络延迟 (500ms)
            setTimeout(() => {
                const batchSize = 6; // 每次追加 6 张
                
                // 创建新的日期分组
                const dateHeader = document.createElement('div');
                dateHeader.className = 'date-header';
                dateHeader.innerText = '更早的照片';
                galleryContainer.appendChild(dateHeader);

                // 创建网格容器
                const grid = document.createElement('div');
                grid.className = 'grid';

                // 生成假数据图片
                for (let i = 0; i < batchSize; i++) {
                    currentCount++;
                    if (currentCount > totalPhotos) break;
                    
                    const card = document.createElement('div');
                    card.className = 'photo-card';
                    card.innerHTML = `
                        <img src="[https://picsum.photos/400/400?random=$](https://picsum.photos/400/400?random=$){currentCount}" alt="photo">
                        <div class="size-badge">${(Math.random() * 20 + 5).toFixed(1)} MB</div>
                    `;
                    grid.appendChild(card);
                }
                
                galleryContainer.appendChild(grid);
                
                // 更新顶部计数器
                displayCountEl.innerText = currentCount;
                
                isLoading = false;
                
                // 检查是否加载完毕
                if (currentCount >= totalPhotos) {
                    sentinel.innerHTML = '<div style="font-size:12px; color:#8e8e93;">没有更多照片了</div>';
                }
            }, 500); // 模拟延迟
        }

        // 使用 IntersectionObserver 监听底部哨兵元素
        const observer = new IntersectionObserver((entries) => {
            // 当哨兵元素进入视口，且不在加载中时，触发加载下一批
            if (entries[0].isIntersecting && !isLoading) {
                loadMorePhotos();
            }
        }, {
            root: null,
            rootMargin: '100px', // 距离底部还有 100px 时就提前触发，保证丝滑
            threshold: 0.1
        });

        // 开始监听
        observer.observe(sentinel);
    </script>
</body>
</html>