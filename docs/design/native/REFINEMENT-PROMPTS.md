Built-in ImageGen was used for the two tablet mockups and the transparent navigation atlas. Final implementation follows princess-refined-reference.png; live data and exact task counts remain native.

## Saved artifacts

- Final daily mockup: /Users/zoomzhao/Workspace/star/docs/design/native/princess-refined-reference.png
- Wallet mockup: /Users/zoomzhao/Workspace/star/docs/design/native/wallet-refined-reference.png
- Shop mockup: /Users/zoomzhao/Workspace/star/docs/design/native/shop-refined-reference.png
- Navigation atlas: /Users/zoomzhao/Workspace/star/android/app/src/main/assets/ui/navigation-atlas.png
- Reward atlas: /Users/zoomzhao/Workspace/star/android/app/src/main/assets/ui/rewards-atlas.png
- Native captures: /Users/zoomzhao/Workspace/star/docs/screenshots/refined-princess-daily.png, refined-princess-wallet.png, refined-princess-shop.png (isolated Android emulator data)

All raster assets were generated using built-in ImageGen. Atlases are consumed directly by native Canvas source rectangles; no external image API or CLI was used.

## polish_wallet_prompt

Use case: ui-mockup. High fidelity native Android tablet landscape UI, 2800x1840 ratio. Use attached FINAL princess dashboard only as design language reference: warm ivory, delicate rose/gold borders, dimensional illustration icons, soft rounded buttons, comfortable density. Global header compact: 吞吞的星星日 left, PAGE TITLE centered to use otherwise empty space, 换装 设置 and gold 可用星星 24 balance at right. Continuous slender ivory sidebar with gold medallion and illustrated clipboard/chest/shop icons, labeled 今日任务 星星宝库 心愿小铺. No gigantic flat buttons. No maximum daily attainable stars, no pagination, no physical device frame. PAGE TITLE 星星宝库, chest navigation selected. Body only 2 well balanced sections: left 32% a beautiful golden ivory treasure card with an illustrated pink/gold star chest, 可用星星 large 24, compact 累计获得 36 and 累计使用 12 below, and 去心愿小铺 button. Beneath this summary in same left column a compact native style seven-day gold/rose bar chart 最近七天 (earned andspent legend), readable small day labels, comfortably spaced. Right 68% a roomy full-height scrollable 星星收支记录 card with thin divider rows: small round star medallion, task title 朗读语文课文, date 09-08 and memo 已通过家长确认, large +2 aligned right; a purchase 冰淇淋 with -5; several other child tasks. Timeline flow, beautifully aligned text, income forest green and expense rose. No fake extra controls or duplicate page title. Balance and chart fixed; only ledger rows scroll. Main point: crafted child friendly collectible treasury, clear hierarchy, no wasted big center top gap, no cramped table.

## polish_shop_prompt

Use case: ui-mockup. High fidelity native Android tablet landscape UI, 2800x1840 ratio. Use attached FINAL princess dashboard only as design language reference: warm ivory, delicate rose/gold borders, dimensional illustration icons, soft rounded buttons, comfortable density. Global header compact: 吞吞的星星日 left, PAGE TITLE centered to use otherwise empty space, 换装 设置 and gold 可用星星 24 balance at right. Continuous slender ivory sidebar with gold medallion and illustrated clipboard/chest/shop icons, labeled 今日任务 星星宝库 心愿小铺. No gigantic flat buttons. No maximum daily attainable stars, no pagination, no physical device frame. PAGE TITLE 心愿小铺, shop navigation selected. Body top compact introduction 让努力，变成小心愿 and secondary text 家长确认兑换后扣除星星, with 兑换记录 quiet button at right. Below a roomy independently scrolling 3-column reward-card grid: lovely storybook raster illustrations of ice cream, toy dinosaur, cinema ticket & popcorn, picnicbasket and wrappedgift. Each ivory card has pastel image area on top, heading 冰淇淋时光 / 新玩具 / 家庭电影夜, short description, gold star icon plus clear price 5 / 30 / 15, and rose pill 我想兑换 or muted 还差 6 星. Show next row partially with thin scroll indicator. No pagination. Reward card prices readable and generous 48dp buttons. The 3-column grid uses the horizontal tablet well. No extra giant hero that crowds products. Include illustrations not unicode emoji.

## polish_rewards_prompt

Use case: illustration-story. ONE production illustration atlas for reward cards in a children's habit game. Exactly 1536x1024 landscape, strict 3 columns by 2 rows, SIX equal 512x512 SQUARE scenes filling their entire cells. No gaps, borders, separators, text, letters, logos or captions. Warm finely painted storybook toy aesthetic, gently dimensional, sunlit, readable simple hero object centered in each cell, premium children's picture-book, harmonized warm ivory sage and rose pastel colors. All subjects fit inside their own cells and no object crosses grid boundaries. Top row left: delicious strawberry vanilla ice cream in striped pink bowl on gingham table with soft pink flowers behind. Top row center: adorable smiling green toy dinosaur standing among a few wooden blocks in a bright playroom. Top row right: striped popcorn bucket and two blank cream movie tickets with decorative star symbols only, cozy cinema bokeh background. Bottom row left: woven picnic basket with fruit and folded gingham cloth on sunny meadow. Bottom row center: beautifully wrapped rose-pink gift box and gold ribbon with soft confetti background. Bottom row right: a stack of colorful blank-cover storybooks and a little glowing star lamp in a cozy reading nook. Scene backgrounds fill every cell. No UI or prices.



## polish_princess_prompt

Use case: ui-mockup
Create a polished high fidelity landscape Android tablet UI redesign for a Chinese children's star habit app, princess pink skin. Reference image is the current implemented UI: retain the pink princess/castle storybook art direction, Chinese task subject animal illustrations, overall left scene/right task organization, but substantially refine the craftsmanship. This is a NEW DESIGN based on the reference, not a screenshot touchup.
Canvas landscape 2800x1840 aspect ratio, front-on app screen, no physical device frame.
Design: warm ivory and pale blush, sophisticated delicate pink/gold detail, softly sculpted rounded surfaces, excellent hierarchy, clear readable Chinese, joyful collectible-game feeling yet uncluttered.
Left narrow rail: one continuous rounded ivory navigation dock with subtle border and shadow, a small gold star medallion at top; three properly illustrated consistent dimensional small icons (task clipboard, star treasure chest, gift shop), labels 今日任务 / 星星宝库 / 心愿小铺. Selected item soft pink tile with small rose indicator, unselected quiet, no giant solid colored blocks, no unicode placeholder symbols.
Header: 吞吞的星星日 on left, compact 换装 and 设置 on right and gold balance pill 星星宝库 24.
Left scene card: title 星星公主, subtitle 每一天，都闪闪发光, princess image. Make 星星收集之旅 a graceful collector card integrated at lower part of scene: petite gold star medallion, fine soft curved constellation trail with small spaced stars, meaningful text 每一份努力，都值得被看见. NO denominators, goals, thresholds or total possible stars. It is a collected-stars keepsake, not a quota meter.
Main right header: 今日任务 and a prominent compact golden badge reading 今日已获得 3 星 near top right of task area. Move earned count HERE from footer. Never display 当日可得 or maximum daily total anywhere.
Next fixed date row 2026-09-08 · 今天 and previous/next/today controls; below clean segmented subject filter 全部 语文 数学 英语 体育 其他, with 英语 selected.
Below the subject bar an independently VERTICALLY SCROLLABLE task area only: spacious two-column cards, English cards 读英文绘本, 英语听力练习, 单词小挑战, 英文儿歌 with storybook fox illustrations, rewards 每次 1 星, limits 每日上限 2 次, gold/empty completion star slots, clearly distinct small status texts and 我完成啦 buttons. Subtle slim vertical scroll indicator inside right region, bottom continuation hint. Header/date/filter/left rail fixed. NO pagination, NO bottom statistics footer. Main controls all visible in landscape.
Avoid: generic enterprise dashboard, flat colored slabs, thin tiny stars, huge borders, over-decoration, any daily attainable total or numeric progress denominator in journey.

## polish_final_prompt

Use case: ui-mockup. Refine the provided princess tablet redesign into the FINAL implementation reference. Maintain its beautiful ivory/rose/gold storybook style and illustrated navigation, but fix uneven space distribution: the top middle is too empty while task controls and cards feel crowded.
Landscape 2800x1840 ratio, app UI only.
ONE balanced global header, only about 8% of screen height: left 吞吞的星星日 in moderate 24dp typography; CENTER the prominent gold reward capsule 今日已获得 3 星 (occupy empty center); right compact 换装 设置 and balance capsule 可用星星 24. No second earned counter anywhere.
Body: continuous slender ivory navigation dock with gold star medallion and dimensional clipboard/chest/shop icons, labels 今日任务 星星宝库 心愿小铺. Selected row is a subtle pale rose rounded tile with narrow rose side indicator, unselected no background blocks. Dock has 3 items with refined generous rhythm but compact icons, not giant slabs.
Left scene panel 30% of remaining width: 星星公主 small title and subtitle, generous princess/castle image, delicate bottom 星星收集之旅 collector keepsake with small centered star medallion, elegant gold constellation swoosh and text 每一份努力，都值得被看见. No quotas, numbers, denominators, thresholds here.
Right task panel 70% of remaining width: just ONE COMPACT row combining 今日任务 title at left and previous/date/next/today controls at right. NO separate heading line, no duplicated tagline.
Second compact subject tab row 全部 语文 数学 英语 体育 其他, 全部 selected.
Then a ROOMIER independently vertically scrollable 2-column task grid, with four complete cards and subtle continuation of next row at bottom indicating more. Cards: 朗读语文课文 / 练习汉字书写 / 口算小练习 / 数学趣味挑战. Panda and rabbit subject illustrations, task reward 每次 1 星 (math challenge2), limit 每日上限 2 次 (writing andchallenge1), small gold star slots at bottom left and 我完成啦 on bottom right. Consistent padding, breathing room, 48dp targets. Thin scrollbar only in task region. No pagination, no giant bottom footer, no 当日可得 or any maximum daily total.
Achieve a balanced crafted product layout; top header no blank central gap; less oversized typography and control slabs, more comfortable card whitespace. Decorative flourishes restrained. Exact Chinese text readable, no placeholders, no device frame.

## polish_icons_prompt

Use case: illustration-story. Production UI sprite atlas for a children's habit game Android app. Create ONE PNG with genuine TRANSPARENT background, exactly 1536x1024, a strictly aligned 3-column x 2-row grid of SIX independent icons. Every cell is 512x512, center each icon inside its cell with at least 80px transparent padding on all sides. No text, no labels, no separators, no UI frames, no shadows extending into another cell.
Style: exquisitely crafted small storybook toy icons, softly sculpted dimensional shapes, warm hand-painted materials, golden beveled edges, clean readable silhouettes at 48dp, gentle highlight and tiny grounded shadow. Match the warm ivory/gold, sage green and princess rose app palettes. Less detail at tiny scale, no photorealism.
Top row DINOSAUR EXPLORER palette sage forest green/gold:
cell 1 top left: cream task clipboard, green clip and three golden checkmarks, small leaf accent.
cell 2 top middle: closed green and golden wooden treasure chest with big gold star clasp.
cell 3 top right: tiny warm cream reward shop with green striped awning, gold star sign and wrapped gift in window.
Bottom row PRINCESS palette blush rose/ivory/gold:
cell 4 bottom left: cream task clipboard with pink clip, three golden checkmarks and tiny rose accent.
cell 5 bottom middle: closed rose pink and gold treasure chest with prominent gold star clasp.
cell 6 bottom right: tiny reward shop with pink striped awning, gold star sign and wrapped gift in window.
Keep exact regular six-cell layout, consistent camera/front three-quarter angle and icon scale. Transparent outside icon silhouettes. NO letters, no words, no extra objects.
