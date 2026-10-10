# BlurView 渲染栈溢出崩溃修复 — 2026-06-27

## 崩溃现象

native 崩溃 in `libhwui.so`，堆栈显示 `RenderNode::prepareTreeImpl` 与
`SkiaDisplayList::prepareListAndChildren` 深度递归（帧 #106~#154+ 循环），
典型征兆为硬件加速渲染管线遍历视图树时栈溢出。

## 根因

`activity_main.xml` 布局结构：

```xml
<BlurTarget id="blurTarget">
    <View id="glassBackground" />
    <BlurView id="contentBlurView" overlayColor="@color/glass_fragment_bg">
        <FrameLayout id="fragmentContainer" />
    </BlurView>
</BlurTarget>
```

`MainActivity.java` 配置：

```java
contentBlurView.setupWith(blurTarget)
    .setBlurRadius(20f)
    .setBlurAutoUpdate(true)
    .setBlurEnabled(true);
```

`contentBlurView` 位于 `blurTarget` 内部，`setupWith(blurTarget)` 让它模糊
自身父容器。在 Android API 31+ 硬件加速渲染管线（RenderEffect）下，
`prepareTreeImpl` 遍历 `blurTarget` 时遇到 `contentBlurView`，而
`contentBlurView` 的模糊源又是 `blurTarget`，形成渲染节点树的循环引用。
`setBlurAutoUpdate(true)` 每帧触发更新，加剧递归深度，最终栈溢出。

`navBlurView` 在 `blurTarget` 外部（兄弟节点），不存在循环，不受影响。

## 修复

### MainActivity.java
- `contentBlurView`: `setBlurRadius(0f)` + `setBlurAutoUpdate(false)` +
  `setBlurEnabled(false)` —— 禁用实时模糊，消除循环引用
- `navBlurView`: `setBlurRadius(12f)`（从 20f 降低）—— 保留实时模糊，
  减少渲染开销

### activity_main.xml
- `contentBlurView` 增加 `android:background="@color/glass_fragment_bg"`
  —— 禁用模糊后仍保持半透明毛玻璃观感（浅色 #CCFAF8F5 / 深色 #CC121212）
- `glassBackground` 色块通过 20% 透明度透出，保留视觉层次

## 影响范围
- 内容区域：从实时模糊降级为半透明叠加色，轻微视觉降级
- 底部导航：保留实时模糊，降低半径减少开销
- 浅色/深色模式：`glass_fragment_bg` 均有对应颜色定义，正常工作

## 修改文件
- `app/src/main/java/com/example/lifetrack/MainActivity.java`
- `app/src/main/res/layout/activity_main.xml`
