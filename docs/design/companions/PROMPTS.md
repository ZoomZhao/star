# 五套皮肤的互动主角

使用内置 image_gen 工具生成，保留原始 PNG 和透明度。应用资源由 cwebp 转换为 900px 高、质量 86 的 WebP。

原始素材：本目录 `dino.png`、`princess.png`、`space.png`、`ocean.png`、`forest.png`。
Android：`android/app/src/main/assets/companions/{key}.webp`。
新皮肤预览与静态网页插画：`android/app/src/main/assets/skins/{key}-hero.webp`、`public/assets/{key}-hero.webp`（space/ocean/forest）。

每次独立生成一个角色，使用以下完整提示模板，将 CHARACTER 替换为下表描述：

> Create one polished children's habit app mascot asset: CHARACTER. Premium soft 3D storybook illustration, rounded clay-like forms, subtle hand-painted texture, warm gentle lighting, charming and expressive, full body centered with generous empty margins on all sides, portrait composition. ONE character only. Genuinely transparent background, preserve alpha, no scenery, no floor, no props outside character, no text, no letters, no watermark, no UI, not a sprite sheet. This is an interactive character which will bounce and wiggle over a separately rendered background.

| key | CHARACTER |
| --- | --- |
| space | a cuddly cream bear astronaut wearing a rounded lavender and ivory spacesuit, clear bubble helmet, little gold star badge, waving one paw |
| ocean | a joyful small turquoise blue whale with cream belly, rosy cheeks, big friendly eyes and curved flippers, upright playful pose |
| forest | an adorable little orange fox explorer with fluffy cream chest and tail tip, sage green scarf, waving a paw |
| dino | a friendly mint green baby dinosaur with cream belly, tiny orange back spikes, explorer scarf, short cute arms, standing upright |
| princess | a friendly young fairytale princess with soft brown hair, tiny gold star crown, flowing rose pink dress, waving, childlike chibi proportions |

Android CompanionView 绘制静止背景，将角色作为单独的图层做旋转、平移和轻微缩放；点击依次切换摇摆、弹跳、扭动。动画不写业务数据；系统禁用动画时只展示静态反馈，View 离开窗口时取消动画与延时回调。
