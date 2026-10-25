# 加载进度：照片页和通知

技术方案，还没实现。记下来是为了和 [requirements.md](../requirements.md) 里「大量照片分批加载」那条对上。

## 现在是什么样

| 位置 | 现在 |
| --- | --- |
| 照片页状态行 | 加载中写「正在加载照片，已显示 231 张」，加载完写「已显示 231 / 1000 张」 |
| 照片页进度条 | 只有状态是「正在加载预览」时才是按比例走的，其余都是无限转圈 |
| 通知 | 加载期间和空闲一样，写「正在运行」，看不出在忙 |

问题有两个：进度条在真正费时间的读目录阶段是无限转圈；通知在整个加载过程中和空闲没有区别。

## 要变成什么样

- 照片页：「正在加载 231 / 1000 张」，进度条按比例走。
- 通知：「正在加载缩略图 231/1000」。

## 关键点：分母必须先知道

现在 `PtpCatalog.catalog()` 是边走边装的：

```kotlin
val storages = parseIdArray(callOk(GET_STORAGE_IDS).data)
for (storage in ids) {
    val roots = parseIdArray(callOk(GET_OBJECT_HANDLES, [storage, 0, 0xFFFFFFFF]).data)
    collectPhotos(storage, roots, ...)   // 递归；每个非文件夹 handle 还要 GET_OBJECT_INFO
}
```

总数是遍历过程中才逐渐长出来的，所以现在根本报不出分母——这也是进度条只能转圈的原因。

## 改法：把「枚举」和「读取」拆开

1. **`enumerateHandles()`**：只走目录结构，收集全部 handle。每一步只调 `GET_OBJECT_HANDLES`，**不调** `GET_OBJECT_INFO`、不取缩略图。文件夹数量远小于照片数量，这一步很快。
2. **`describe(handles)`**：对每个 handle 调 `GET_OBJECT_INFO` + `GET_THUMB`。这才是费时间的一段，也是要报进度的一段。

拆开之后，`hands.size` 就是总数，在第二步开始前就拿到了。

## 落地

**shared / `PtpCatalog`**
- 拆成 `enumerateHandles(): List<Long>` 和 `describe(handles, onPhoto)`。
- 保留一个 `catalog()` 作为两者的组合，现有调用点不用一次全改。

**androidApp / `CameraRoll`**
- `open()` 先枚举再描述，枚举结束通过新回调 `onTotal(count)` 抛给上层。
- 已有的 `onPreview(batch)` 保持不动。

**androidApp / `PhotoViewModel`**
- 新增 `loadingTotal`、`loadingDone`。
- 状态行改成「正在加载 $loadingDone / $loadingTotal 张」。
- 加载过程中也喂 `onSessionText`，让通知能显示同一组数字。

**androidApp / `PhotoScreen`**
- 进度条的 fraction 改成 `loadingDone / loadingTotal`，去掉「只有正在加载预览才算」那个特例。

**通知节流**
- 不能每张都推送。按「每 20 张或每 500 毫秒，谁先到算谁」更新一次，和保存进度那条一样做去重。

## 和「按张数 / 按日期分批加载」的关系

`requirements.md` 那一条是以后要多照片时只加载一部分（最近 100 张，或最近一周 / 半年）。

拆出 `enumerateHandles` 正好是它的前置：枚举拿到全部 handle 和总数之后，按策略截取要描述的那一段，进度分母就是截取后的数量。所以这一步做完，那条只需要加「截取策略」，不用再改加载流程。

## 验收

- 相机里一千张时，照片页写「正在加载 231 / 1000 张」，进度条按比例走，不再无限转圈。
- 通知写「正在加载缩略图 231/1000」，不会每张都闪。
- 加载完成状态回到「已显示 N / M 张」，通知回到「正在运行」。
- 只有几张照片时也不出错（分母为 1、为 0 都要稳）。
- 分批加载落地后，分母是实际要加载的数量，不是存储卡总数。
