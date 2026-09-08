# Web 组件细节

参考 [Fluid Functionalism](https://github.com/mickadesign/fluid-functionalism) 的
[Motion Guidelines](https://github.com/mickadesign/fluid-functionalism/blob/main/motion-guidelines.md)、
Tabs 和 Dialog 组件，适配现有 React / 原生 HTML 组件与恐龙、公主两套皮肤。

- `src/lib/springs.ts` 复用上游 spring 令牌：80ms 微交互、160ms 选中指示、240ms 弹窗；退出使用更短的 tween。
- `src/components/Fluid.tsx` 提供按钮按压与占位加载、科目滑动选中块、弹窗进出、可暂停和关闭的提示。
- 原生 `dialog` 保留焦点约束；标题获焦、关闭回到入口，区分遮罩和内部留白，提交时阻止关闭，错误留在表单内。
- 日期固定为周一至周日，历史日期可回到今天；科目、皮肤、星期和导航暴露选中状态。科目支持方向键、Home、End。
- 管理员页面提供家庭与账号概览、账号搜索和组合筛选；创建家庭后自动选入账号分配表单，成功分配后清除筛选以展示新账号。备份下载与恢复操作分层显示，窄屏账号列表转为卡片。
- `MotionConfig reducedMotion="user"` 配合显式减少运动与 CSS 媒体查询，让系统减少动态效果设置覆盖位移、缩放、选中块和加载动画。
- 不依赖变量字体的字重动画，中文按钮使用固定字重和等宽选项，防止切换时文字跳动。

保留的上游代码与参考适配遵循 MIT 许可，完整声明位于 [licenses/fluid-functionalism.txt](licenses/fluid-functionalism.txt)。
