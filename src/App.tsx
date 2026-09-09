import { useCallback, useEffect, useRef, useState, useId, cloneElement } from 'react';
import type { FormEvent, ReactNode, ReactElement } from 'react';
import { AnimatePresence } from 'framer-motion';
import { Button, InteractionSurface, Modal, SubjectPicker, Toast } from './components/Fluid';
import {
  Star,
  ClipboardList,
  Wallet,
  Store,
  ShieldCheck,
  Settings,
  Volume2,
  VolumeX,
  ChevronLeft,
  ChevronRight,
  Plus,
  Check,
  X,
  LogOut,
  Leaf,
  Sparkles,
  ArrowDownLeft,
  ArrowUpRight,
  BookOpen,
  Timer,
  Gift,
  Download,
  Upload,
  Users,
  RefreshCw,
  CalendarDays,
  LockKeyhole,
  Heart,
  Pencil,
  Blocks,
  Moon,
  IceCreamBowl,
  Clapperboard,
  TentTree,
  Gamepad2,
  Info,
  Search,
  Building2,
  UserRoundX,
} from 'lucide-react';
import { Haptics, ImpactStyle } from '@capacitor/haptics';
import { api, initToken, saveToken, requestId, isNative, serverUrl, setServerUrl } from './api';
import presets from '../shared/task-templates.json';
const SKINS = [
  {
    key: 'dino',
    label: '恐龙探险',
    title: '星星探险家',
    asset: 'island-portrait',
    mobile: 'island',
    alt: '恐龙探险家和装满星星的宝箱',
  },
  {
    key: 'princess',
    label: '公主花园',
    title: '星星公主',
    asset: 'princess-hero',
    mobile: 'princess-hero',
    alt: '星星公主和魔法花园',
  },
  {
    key: 'space',
    label: '太空小熊',
    title: '星际小熊',
    asset: 'space-hero',
    mobile: 'space-hero',
    alt: '挥手的太空小熊',
  },
  {
    key: 'ocean',
    label: '海底小鲸',
    title: '小鲸奇遇记',
    asset: 'ocean-hero',
    mobile: 'ocean-hero',
    alt: '快乐的海底小鲸',
  },
  {
    key: 'forest',
    label: '森林小狐',
    title: '森林小队长',
    asset: 'forest-hero',
    mobile: 'forest-hero',
    alt: '戴围巾的森林小狐',
  },
];

const subjects: Record<string, string> = {
  chinese: '语文',
  math: '数学',
  english: '英语',
  sports: '体育',
  other: '其他',
};
import type { Dashboard, User, Task, Rule, Reward, Entry } from './types';
const icons: Record<string, typeof Star> = {
  book: BookOpen,
  brush: Sparkles,
  blocks: Blocks,
  leaf: Leaf,
  bed: Moon,
  pencil: Pencil,
  heart: Heart,
  icecream: IceCreamBowl,
  toy: Gamepad2,
  movie: Clapperboard,
  picnic: TentTree,
  gift: Gift,
};
const weekdays = ['日', '一', '二', '三', '四', '五', '六'];
function shift(d: string, n: number) {
  const dt = new Date(d + 'T12:00:00Z');
  dt.setUTCDate(dt.getUTCDate() + n);
  return dt.toISOString().slice(0, 10);
}
function currentDate() {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
}
function dateText(d: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  }).format(new Date(d + 'T12:00:00+08:00'));
}
function timeText(d: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(d));
}
function StarIcon({ size = 22, filled = true }: { size?: number; filled?: boolean }) {
  return <Star size={size} className={filled ? 'star filled' : 'star'} strokeWidth={1.7} />;
}
function Tile({ icon, size = '', subject }: { icon: string; size?: string; subject?: string }) {
  if (subject)
    return (
      <img
        className={`tile subject-art ${size}`}
        src={`/assets/subjects/${subject}.webp`}
        alt={subjects[subject] || '其他'}
      />
    );
  const Icon = icons[icon] || Star;
  return (
    <div className={`tile tile-${icon} ${size}`}>
      <Icon strokeWidth={1.6} />
      <i>✦</i>
    </div>
  );
}
function Empty({ children }: { children: ReactNode }) {
  return (
    <div className="empty">
      <Leaf size={32} />
      <p>{children}</p>
    </div>
  );
}
function Field({ label, children }: { label: string; children: ReactNode }) {
  const fieldId = useId();
  return (
    <div className="field">
      <label htmlFor={fieldId}>{label}</label>
      {cloneElement(children as ReactElement<{ id?: string }>, { id: fieldId })}
    </div>
  );
}
function Login({ done }: { done: (u: User) => void }) {
  const [username, setUsername] = useState(''),
    [password, setPassword] = useState(''),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [demo, setDemo] = useState(false),
    [url, setUrl] = useState(serverUrl()),
    [config, setConfig] = useState(isNative && !serverUrl());
  useEffect(() => {
    api<{ demo: boolean }>('/api/config')
      .then((r) => setDemo(r.demo))
      .catch(() => {});
  }, []);
  async function login(e?: FormEvent, u = username, p = password) {
    e?.preventDefault();
    setBusy(true);
    setError('');
    try {
      const r = await api<{ token: string; user: User }>('/api/login', 'POST', {
        username: u,
        password: p,
      });
      await saveToken(r.token);
      done(r.user);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="login">
      <section className="login-art">
        <div className="brand-badge">
          <StarIcon /> 每一个小小努力，都值得一颗星
        </div>
        <h1>
          星星
          <br />
          探险家<span>STAR EXPLORER</span>
        </h1>
        <p>
          和小恐龙一起，
          <br />
          把好习惯变成闪亮的星星。
        </p>
        <img src="/assets/island.webp" alt="小恐龙拿着地图，在藏有星星的岛屿探险" />
      </section>
      <section className="login-panel">
        <div className="login-inner">
          <div className="eyebrow">
            <Leaf size={16} /> 一起开始今天的冒险
          </div>
          <h2>欢迎回到星星岛</h2>
          <p className="muted">用分配好的账号登录，继续你的成长旅程。</p>
          <form onSubmit={login}>
            <Field label="账号">
              <input
                autoComplete="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                required
                placeholder="请输入账号"
              />
            </Field>
            <Field label="密码">
              <input
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                placeholder="请输入密码"
              />
            </Field>
            {error && (
              <div className="error" role="alert">
                {error}
              </div>
            )}
            <Button className="primary full" loading={busy}>
              出发，去星星岛 <ChevronRight size={19} />
            </Button>
          </form>
          <p className="login-tip">
            <LockKeyhole size={14} /> 账号由管理员提前分配，无需注册
          </p>
          {demo && (
            <div className="demo">
              <span>本地演示 · 选择身份体验</span>
              <div>
                {[
                  ['child', 'child123', '小朋友'],
                  ['parent', 'parent123', '家长'],
                  ['admin', 'local-admin-2026', '管理员'],
                ].map(([u, p, label]) => (
                  <Button key={u} disabled={busy} onClick={() => login(undefined, u, p)}>
                    {label}
                  </Button>
                ))}
              </div>
            </div>
          )}
          {isNative && (
            <Button className="text-btn" onClick={() => setConfig(!config)}>
              配置服务器地址
            </Button>
          )}
          {config && (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                try {
                  setServerUrl(url);
                  setConfig(false);
                  setError('');
                  api<{ demo: boolean }>('/api/config')
                    .then((r) => setDemo(r.demo))
                    .catch((e) => setError(e.message));
                } catch (e) {
                  setError((e as Error).message);
                }
              }}
            >
              <Field label="服务器 HTTPS 地址">
                <input
                  value={url}
                  onChange={(e) => setUrl(e.target.value)}
                  placeholder="https://stars.example.com"
                />
              </Field>
              <Button className="secondary full">保存地址</Button>
            </form>
          )}
        </div>
        <small className="login-footer">小小的坚持，大大的成长 🌱</small>
      </section>
    </main>
  );
}
type Dialog =
  | { type: 'task'; task: Task }
  | { type: 'rule'; rule?: Rule; task?: Task }
  | { type: 'reward'; reward?: Reward }
  | { type: 'redeem'; reward: Reward }
  | { type: 'entry' }
  | { type: 'reverse'; entry: Entry }
  | { type: 'settings' }
  | { type: 'celebrate'; title: string; text: string };
export default function App() {
  const [user, setUser] = useState<User | null>(null),
    [loading, setLoading] = useState(true),
    [children, setChildren] = useState<User[]>([]),
    [childrenLoaded, setChildrenLoaded] = useState(false),
    [childrenRetry, setChildrenRetry] = useState(0),
    [childId, setChildId] = useState(''),
    [date, setDate] = useState(currentDate()),
    [data, setData] = useState<Dashboard | null>(null),
    [page, setPage] = useState('today'),
    [rewardFilter, setRewardFilter] = useState('all'),
    [error, setError] = useState(''),
    [notice, setNotice] = useState(''),
    [busy, setBusy] = useState(false),
    [dialog, setDialog] = useState<Dialog | null>(null),
    [sound, setSound] = useState(localStorage.getItem('star-sound') === 'true'),
    [online, setOnline] = useState(navigator.onLine),
    [cheer, setCheer] = useState(''),
    [subject, setSubject] = useState('chinese'),
    [taskPage, setTaskPage] = useState(0),
    [skin, setSkin] = useState(localStorage.getItem('star-skin') || 'dino'),
    [skinOpen, setSkinOpen] = useState(false);
  const skinTheme = SKINS.find((option) => option.key === skin) || SKINS[0];
  useEffect(() => {
    document.documentElement.dataset.skin = skin;
    localStorage.setItem('star-skin', skin);
  }, [skin]);
  useEffect(() => setTaskPage(0), [date, subject, childId]);
  const filteredTasks = data?.tasks.filter((t) => t.subject === subject) || [];
  const visiblePage = Math.min(taskPage, Math.max(0, Math.ceil(filteredTasks.length / 4) - 1));
  const subjectPicker = <SubjectPicker options={subjects} value={subject} onChange={setSubject} />;
  const working = useRef(false),
    lastBalance = useRef<number | null>(null),
    observedToday = useRef(currentDate()),
    fetchVersion = useRef(0);
  const isParent = user?.role === 'parent' || user?.role === 'admin';
  useEffect(() => {
    initToken()
      .then(() => api<User>('/api/me'))
      .then(setUser)
      .catch(() => {})
      .finally(() => setLoading(false));
    const reset = () => {
      fetchVersion.current++;
      setUser(null);
      setData(null);
      setChildren([]);
      setChildId('');
      setDialog(null);
    };
    window.addEventListener('star-session-expired', reset);
    const on = () => setOnline(true),
      off = () => setOnline(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => {
      window.removeEventListener('star-session-expired', reset);
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
    };
  }, []);
  useEffect(() => {
    if (!user) return;
    let active = true;
    setChildrenLoaded(false);
    setError('');
    setPage(user.role === 'admin' ? 'admin' : 'today');
    setChildId('');
    setData(null);
    lastBalance.current = null;
    api<User[]>('/api/children')
      .then((cs) => {
        if (!active) return;
        setChildren(cs);
        setChildrenLoaded(true);
        setChildId(cs[0]?.id || '');
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [user, childrenRetry]);
  const refresh = useCallback(async () => {
    if (!user) return;
    if (!childId) {
      setChildrenRetry((n) => n + 1);
      return;
    }
    const n = ++fetchVersion.current;
    const d = await api<Dashboard>(`/api/children/${childId}/dashboard?date=${date}`);
    if (n !== fetchVersion.current) return;
    setData(d);
    setError('');
    if (
      lastBalance.current !== null &&
      d.wallet.balance > lastBalance.current &&
      user?.role === 'child'
    ) {
      setNotice(`收到 ${d.wallet.balance - lastBalance.current} 颗星星！你的努力被看见啦 ✨`);
    }
    lastBalance.current = d.wallet.balance;
  }, [childId, date, user]);
  useEffect(() => {
    setData(null);
    lastBalance.current = null;
    if (childId) refresh().catch((e) => setError(e.message));
    return () => {
      fetchVersion.current++;
    };
  }, [refresh, childId]);
  useEffect(() => {
    if (!user || !childId) return;
    const tick = () => {
      if (
        document.visibilityState !== 'visible' ||
        working.current ||
        dialog ||
        document.activeElement?.matches('input, textarea, select, [contenteditable=true]')
      )
        return;
      const now = currentDate();
      const followToday = date === observedToday.current;
      observedToday.current = now;
      if (followToday && date !== now) {
        setDate(now);
        return;
      }
      refresh().catch(() => {});
    };
    const t = setInterval(tick, 20000);
    window.addEventListener('focus', tick);
    window.addEventListener('online', tick);
    document.addEventListener('visibilitychange', tick);
    return () => {
      clearInterval(t);
      window.removeEventListener('focus', tick);
      window.removeEventListener('online', tick);
      document.removeEventListener('visibilitychange', tick);
    };
  }, [user, childId, refresh, date, dialog]);
  async function logout() {
    await api('/api/logout', 'POST').catch(() => {});
    await saveToken('');
    fetchVersion.current++;
    setUser(null);
    setData(null);
    setChildren([]);
    setChildId('');
    setDate(currentDate());
    setDialog(null);
    setError('');
  }
  function feedback() {
    if (isNative) Haptics.impact({ style: ImpactStyle.Light }).catch(() => {});
    if (sound) {
      try {
        const ctx = new AudioContext();
        [523.25, 659.25, 783.99].forEach((f, i) => {
          const o = ctx.createOscillator(),
            g = ctx.createGain();
          o.connect(g);
          g.connect(ctx.destination);
          o.frequency.value = f;
          g.gain.setValueAtTime(0.05, ctx.currentTime + i * 0.09);
          g.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.18 + i * 0.09);
          o.start(ctx.currentTime + i * 0.09);
          o.stop(ctx.currentTime + 0.2 + i * 0.09);
        });
        setTimeout(() => ctx.close(), 800);
      } catch {
        /* sound is optional */
      }
    }
  }
  async function act(fn: () => Promise<unknown>, message?: string) {
    if (working.current) return false;
    working.current = true;
    setBusy(true);
    setError('');
    try {
      await fn();
      await refresh().catch(() => setNotice('操作已保存，暂时未能刷新页面，请稍后刷新'));
      if (message) setNotice(message);
      return true;
    } catch (e) {
      setError((e as Error).message);
      return false;
    } finally {
      working.current = false;
      setBusy(false);
    }
  }
  async function submitTask(task: Task, note: string, unitStars = task.stars, quantity = 1) {
    const ok = await act(() =>
      api(
        `/api/tasks/${task.id}/submit`,
        'POST',
        { note, ...(isParent ? { unit_stars: unitStars, quantity } : {}) },
        requestId(),
      ),
    );
    if (ok) {
      feedback();
      setDialog({
        type: 'celebrate',
        title: isParent ? '星星到账啦！' : '太棒啦，任务已提交！',
        text: isParent
          ? `已经获得 ${unitStars * quantity} 颗星星，每一次努力都算数。`
          : '等家长确认后，星星就会飞进口袋。你可以继续探索其他任务！',
      });
    }
  }
  async function review(
    id: string,
    approve: boolean,
    kind = 'submissions',
    award?: { unit_stars: number; quantity: number },
  ) {
    await act(
      () =>
        api(`/api/${kind}/${id}/review`, 'POST', {
          approve,
          ...award,
          note: approve ? '做得真棒！' : '再试一次吧，完成后可以重新提交。',
        }),
      approve ? '已确认，星星账本已更新' : '已退回，孩子可以查看反馈',
    );
  }
  const total = data?.tasks.reduce((s, t) => s + t.stars * t.daily_limit, 0) || 0,
    earned = data?.dayEarned || 0,
    complete = data?.tasks.reduce((s, t) => s + t.approved, 0) || 0,
    limit = data?.tasks.reduce((s, t) => s + t.daily_limit, 0) || 0,
    pending =
      (data?.reviews.length || 0) +
      (data?.redemptions.filter((r) => r.status === 'pending').length || 0);
  const nav =
    user?.role === 'admin'
      ? [{ id: 'admin', title: '管理中心', icon: ShieldCheck }]
      : [
          { id: 'today', title: '今日任务', icon: ClipboardList },
          ...(isParent
            ? [
                { id: 'review', title: '待确认', icon: ShieldCheck },
                { id: 'rules', title: '任务规则', icon: CalendarDays },
              ]
            : []),
          { id: 'wallet', title: '星星口袋', icon: Wallet },
          { id: 'shop', title: '奖励小铺', icon: Store },
        ];
  if (loading)
    return (
      <div className="boot" role="status">
        <StarIcon size={52} />
        <p>星星岛准备中…</p>
      </div>
    );
  if (!user) return <Login done={setUser} />;
  return (
    <InteractionSurface
      busy={busy}
      error={error}
      className={`app ${isParent ? 'parent-app' : 'child-app'} page-${page}`}
    >
      <header className="topbar">
        <Button className="identity" onClick={() => setDialog({ type: 'settings' })}>
          <div className="avatar">
            {user.role === 'child' ? '🦖' : user.role === 'parent' ? '🌿' : '🛡️'}
          </div>
          <div>
            <strong>
              {user.role === 'child'
                ? `${user.name}的探险日`
                : user.role === 'parent'
                  ? '家长空间'
                  : '管理中心'}
            </strong>
            <span>{user.role === 'child' ? '每天一点点，一起变更棒' : user.name}</span>
          </div>
        </Button>
        <div className="top-date">
          <CalendarDays size={17} />
          {dateText(date)}
        </div>
        <div className="top-actions">
          <Button className="secondary" onClick={() => setSkinOpen(true)}>
            {user.role === 'admin' ? '主题' : '换装'}
          </Button>
          {user.role !== 'admin' && (
            <Button
              className="icon-btn sound-toggle"
              aria-label={sound ? '关闭音效' : '打开音效'}
              aria-pressed={sound}
              onClick={() => {
                setSound(!sound);
                localStorage.setItem('star-sound', String(!sound));
              }}
            >
              {sound ? <Volume2 /> : <VolumeX />}
            </Button>
          )}
          <Button
            className="icon-btn"
            aria-label="设置"
            onClick={() => setDialog({ type: 'settings' })}
          >
            <Settings />
          </Button>
          {data && user.role !== 'admin' && (
            <Button
              className="balance-pill"
              aria-label={`可用 ${data.wallet.balance} 颗星星，查看星星口袋`}
              onClick={() => setPage('wallet')}
            >
              <StarIcon />
              <strong>{data.wallet.balance}</strong>
            </Button>
          )}
        </div>
      </header>
      <nav className="navigation" aria-label="主导航">
        {nav.map((n) => (
          <Button
            key={n.id}
            className={page === n.id ? 'active' : ''}
            aria-current={page === n.id ? 'page' : undefined}
            onClick={() => {
              setPage(n.id);
              setError('');
            }}
          >
            <n.icon size={27} />
            <span>{n.title}</span>
            {n.id === 'review' && pending > 0 && <b className="badge">{pending}</b>}
          </Button>
        ))}
        <Button className="nav-account" onClick={() => setDialog({ type: 'settings' })}>
          <Users size={24} />
          <span>{isParent ? '账号设置' : '家长入口'}</span>
        </Button>
      </nav>
      <main className="workspace">
        {!online && <div className="error">网络已断开。请恢复连接后提交任务。</div>}
        {error && (
          <div className="error global-error" role="alert">
            {error}
            <Button aria-label="关闭错误" onClick={() => setError('')}>
              <X size={16} />
            </Button>
          </div>
        )}
        {user.role !== 'child' && user.role !== 'admin' && (
          <div className="child-picker">
            <span>正在陪伴</span>
            <select
              value={childId}
              onChange={(e) => setChildId(e.target.value)}
              aria-label="选择小朋友"
            >
              {children.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
            <Button
              className="text-btn"
              onClick={() => refresh().catch((e) => setError(e.message))}
            >
              <RefreshCw size={15} /> 刷新
            </Button>
          </div>
        )}
        {user.role === 'admin' ? (
          <AdminPanel busy={busy} act={act} logout={logout} />
        ) : !childId ? (
          <Empty>
            {childrenLoaded ? '还没有分配小朋友，请联系管理员。' : '正在加载家庭信息…'}
            {(childrenLoaded || error) && (
              <Button className="secondary" onClick={() => setChildrenRetry((n) => n + 1)}>
                重新加载
              </Button>
            )}
          </Empty>
        ) : !data ? (
          <div className="loading-panel" role="status">
            <StarIcon size={38} />
            <p>正在寻找今天的星星…</p>
            {error && (
              <Button
                className="secondary"
                onClick={() => refresh().catch((e) => setError(e.message))}
              >
                重新加载
              </Button>
            )}
          </div>
        ) : (
          <>
            {page === 'today' && (
              <div className="daily-layout">
                <aside className="adventure">
                  <picture>
                    <source media="(min-width:901px)" srcSet={`/assets/${skinTheme.asset}.webp`} />
                    <img
                      className="island"
                      src={`/assets/${skinTheme.mobile}.webp`}
                      alt={skinTheme.alt}
                    />
                  </picture>
                  <div className="hero-copy">
                    <span className="eyebrow">LITTLE STEPS, BIG ADVENTURES</span>
                    <h1>{skinTheme.title}</h1>
                    <p>✦ 今天也要闪闪发光 ✦</p>
                  </div>
                  <Button
                    className="dino-touch"
                    aria-label={skin === 'dino' ? '和小恐龙打招呼' : `和${skinTheme.title}打招呼`}
                    onClick={() => {
                      feedback();
                      setCheer(
                        [
                          '你努力的样子，闪闪发光！',
                          '每完成一件小事，就离梦想近一步。',
                          '准备好了吗？一起去收集星星！',
                        ][Math.floor(Math.random() * 3)],
                      );
                      setTimeout(() => setCheer(''), 3500);
                    }}
                  />
                  {cheer && (
                    <div className="speech" role="status">
                      {cheer}
                    </div>
                  )}
                  <div className="adventure-progress">
                    <div className="section-line">
                      <strong>
                        <Leaf size={17} /> 今日探险进度
                      </strong>
                      <span>
                        <b>{complete}</b> / {limit}
                      </span>
                    </div>
                    <div className="milestones">
                      {[0.25, 0.5, 0.75, 1].map((n, i) => (
                        <div key={n}>
                          <StarIcon size={36} filled={total > 0 && earned / total >= n} />
                          <small>{['萌芽', '出发', '探索', '闪耀'][i]}</small>
                        </div>
                      ))}
                    </div>
                    <p>
                      {complete === limit && limit > 0
                        ? '今日探险完成！你是闪亮的小小探险家。'
                        : '每一次小小的坚持，都在让你长大。'}
                    </p>
                  </div>
                </aside>
                <section className="daily-content">
                  <div className="page-heading">
                    <div>
                      <span className="eyebrow">TODAY'S ADVENTURE</span>
                      <h2>
                        <Leaf /> {date === data.today ? '今日任务' : '每日任务'}
                      </h2>
                    </div>
                    <div className="date-input">
                      {date !== data.today && (
                        <Button
                          type="button"
                          className="return-today"
                          onClick={() => setDate(data.today)}
                        >
                          回到今天
                        </Button>
                      )}
                      <CalendarDays size={18} />
                      <input
                        type="date"
                        value={date}
                        onChange={(e) => {
                          if (e.target.value) setDate(e.target.value);
                        }}
                        aria-label="任务日期"
                      />
                    </div>
                  </div>
                  <DateStrip date={date} today={data.today} setDate={setDate} />
                  <div className="subject-control">{subjectPicker}</div>
                  <div className="task-grid">
                    {filteredTasks.slice(visiblePage * 4, visiblePage * 4 + 4).map((t) => (
                      <article
                        key={t.id}
                        className={`task-card ${t.approved >= t.daily_limit ? 'completed' : ''}`}
                      >
                        <div className="task-main">
                          <Tile icon={t.icon} subject={t.subject} />
                          <div className="task-copy">
                            <Button
                              className="task-title"
                              onClick={() => setDialog({ type: 'task', task: t })}
                            >
                              {t.title}
                              <Info size={14} />
                            </Button>
                            <p>
                              每次 <b>{t.stars}</b> 星 · 每天最多 <b>{t.daily_limit}</b> 次 ·
                              家长可调整星星和次数
                            </p>
                            <div className="task-stars">
                              {Array.from({ length: Math.min(t.daily_limit, 5) }, (_, i) => (
                                <span
                                  key={i}
                                  className={
                                    i >= t.approved && i < t.approved + t.pending
                                      ? 'pending-star'
                                      : ''
                                  }
                                >
                                  <StarIcon size={25} filled={i < t.approved} />
                                </span>
                              ))}
                              {t.daily_limit > 5 && (
                                <small>
                                  {t.approved}/{t.daily_limit}
                                </small>
                              )}
                            </div>
                          </div>
                        </div>
                        <div className="task-bottom">
                          <span className="task-status">
                            {t.approved >= t.daily_limit ? (
                              <>
                                <Check size={14} /> 今日已达成
                              </>
                            ) : t.pending > 0 ? (
                              <>
                                <Timer size={14} /> {t.pending} 次待确认
                              </>
                            ) : (
                              <>{t.approved > 0 ? '继续加油' : '小小行动，大大成长'}</>
                            )}
                          </span>
                          <Button
                            className={
                              t.approved >= t.daily_limit
                                ? 'done-button'
                                : t.approved + t.pending >= t.daily_limit
                                  ? 'waiting-button'
                                  : 'primary'
                            }
                            disabled={
                              busy ||
                              !online ||
                              t.approved + t.pending >= t.daily_limit ||
                              date > data.today ||
                              (!isParent && date !== data.today)
                            }
                            onClick={() => setDialog({ type: 'task', task: t })}
                          >
                            {t.approved >= t.daily_limit
                              ? '完成啦'
                              : t.approved + t.pending >= t.daily_limit
                                ? '等待确认'
                                : date > data.today
                                  ? '还没到这天'
                                  : isParent
                                    ? '代为完成'
                                    : '我完成啦'}
                            {t.approved + t.pending < t.daily_limit && <ChevronRight size={16} />}
                          </Button>
                        </div>
                      </article>
                    ))}
                  </div>
                  {!filteredTasks.length && (
                    <Empty>
                      {date > data.today
                        ? '这一天还没有安排任务。'
                        : '还没有任务，请家长来安排今天的小冒险吧。'}
                    </Empty>
                  )}
                  <div className="task-pagination" aria-label="任务分页">
                    <Button
                      className="secondary"
                      disabled={visiblePage === 0}
                      onClick={() => setTaskPage(visiblePage - 1)}
                    >
                      上一页
                    </Button>
                    <span aria-live="polite" aria-atomic="true">
                      {visiblePage + 1} / {Math.max(1, Math.ceil(filteredTasks.length / 4))}
                    </span>
                    <Button
                      className="secondary"
                      disabled={(visiblePage + 1) * 4 >= filteredTasks.length}
                      onClick={() => setTaskPage(visiblePage + 1)}
                    >
                      下一页
                    </Button>
                  </div>
                  <div className="daily-summary">
                    <div>
                      <StarIcon size={31} />
                      <span>{date === data.today ? '今日' : '当日'}任务获得</span>
                      <strong>{earned}</strong>
                      <small>星</small>
                    </div>
                    <div>
                      <span>任务可得</span>
                      <strong>{total}</strong>
                      <small>星</small>
                    </div>
                    <span className="summary-chest">🎁</span>
                  </div>
                  <p className="footnote">
                    <ShieldCheck size={14} /> 家长确认后，星星才会放进口袋 ·{' '}
                    {data.tasks.reduce((s, t) => s + t.pending, 0)} 次等待确认
                  </p>
                </section>
              </div>
            )}
            {page === 'wallet' && (
              <section className="wallet-page">
                <PageHeading eyebrow="YOUR LITTLE TREASURES" title="星星口袋" icon={<Wallet />} />
                <div className="wallet-layout">
                  <section>
                    <div className="wallet-card">
                      <div className="coin">
                        <StarIcon size={86} />
                      </div>
                      <div className="wallet-number">
                        <span>每一颗星，都记录着你的努力</span>
                        <strong>{data.wallet.balance}</strong>
                        <p>可用星星</p>
                      </div>
                      <div className="wallet-totals">
                        <div>
                          累计获得
                          <strong>
                            {data.wallet.earned}
                            <StarIcon size={18} />
                          </strong>
                        </div>
                        <div>
                          累计消耗
                          <strong>
                            {data.wallet.spent}
                            <StarIcon size={18} />
                          </strong>
                        </div>
                      </div>
                    </div>
                    <div className="card growth-card">
                      <div className="section-line">
                        <h3>
                          <Leaf size={19} /> 闪亮的每一天
                        </h3>
                        <span>近 7 天获得</span>
                      </div>
                      <div className="chart">
                        {data.week.map((d) => (
                          <div className="chart-column" key={d.date}>
                            <span>{d.earned}</span>
                            <div
                              style={{
                                height: `${Math.max(5, (d.earned / Math.max(1, ...data.week.map((x) => x.earned))) * 110)}px`,
                              }}
                              className={d.date === data.today ? 'today-bar' : ''}
                            />
                            <small>{d.date.slice(5).replace('-', '.')}</small>
                          </div>
                        ))}
                      </div>
                    </div>
                    {isParent && (
                      <Button className="primary full" onClick={() => setDialog({ type: 'entry' })}>
                        <Plus size={19} /> 记一笔星星
                      </Button>
                    )}
                  </section>
                  <Ledger
                    entries={data.ledger}
                    isParent={isParent}
                    reverse={(entry) => setDialog({ type: 'reverse', entry })}
                  />
                </div>
              </section>
            )}
            {page === 'shop' && (
              <section className="shop-page">
                <PageHeading
                  eyebrow="A LITTLE WISH, A HAPPY REWARD"
                  title={isParent ? '奖励小铺管理' : '奖励小铺'}
                  icon={<Store />}
                  action={
                    isParent ? (
                      <Button className="primary" onClick={() => setDialog({ type: 'reward' })}>
                        <Plus size={17} /> 添加奖励
                      </Button>
                    ) : undefined
                  }
                />
                {isParent && (
                  <div className="reward-management">
                    <label>
                      奖励状态{' '}
                      <select
                        aria-label="奖励状态"
                        value={rewardFilter}
                        onChange={(e) => setRewardFilter(e.target.value)}
                      >
                        <option value="all">全部奖励</option>
                        <option value="active">已上架</option>
                        <option value="inactive">已下架</option>
                      </select>
                    </label>
                    <p>奖励由本家庭共用，下架后可重新上架。</p>
                    <Button className="secondary" onClick={() => setPage('review')}>
                      审核兑换申请
                    </Button>
                  </div>
                )}
                <div className="shop-banner">
                  <div>
                    <span className="eyebrow">把努力，变成喜欢的事物</span>
                    <h3>
                      存下小星星，
                      <br />
                      实现小心愿。
                    </h3>
                    <p>
                      <StarIcon size={17} /> 你的口袋里有 <b>{data.wallet.balance}</b> 颗星星
                    </p>
                  </div>
                  <div className="shop-gift">
                    🎁<span>✦</span>
                  </div>
                </div>
                <div className="rewards-grid">
                  {data.rewards
                    .filter(
                      (r) =>
                        !isParent ||
                        rewardFilter === 'all' ||
                        (rewardFilter === 'active' ? !!r.active : !r.active),
                    )
                    .map((r) => {
                      const waiting = data.redemptions.some(
                        (x) => x.reward_id === r.id && x.status === 'pending',
                      );
                      return (
                        <article className="reward-card" key={r.id}>
                          <Tile icon={r.icon} size="large" />
                          <h3>{r.title}</h3>
                          {isParent && (
                            <span className={`tag ${r.active ? 'approved' : 'rejected'}`}>
                              {r.active ? '已上架' : '已下架'}
                            </span>
                          )}
                          <p>{r.description}</p>
                          <div className="reward-bottom">
                            <strong>
                              <StarIcon size={20} />
                              {r.cost}
                            </strong>
                            <Button
                              className="secondary"
                              disabled={
                                busy ||
                                (!isParent && waiting) ||
                                !online ||
                                (!isParent && r.cost > data.wallet.balance)
                              }
                              onClick={() =>
                                setDialog(
                                  isParent
                                    ? { type: 'reward', reward: r }
                                    : { type: 'redeem', reward: r },
                                )
                              }
                            >
                              {isParent
                                ? '编辑奖励'
                                : waiting
                                  ? '等待确认'
                                  : r.cost > data.wallet.balance
                                    ? `还差 ${r.cost - data.wallet.balance} 星`
                                    : '我想兑换'}
                            </Button>
                          </div>
                          {isParent && (
                            <Button
                              className="secondary reward-status-action"
                              disabled={busy || !online}
                              onClick={() =>
                                act(
                                  () =>
                                    api(`/api/rewards/${r.id}`, 'PATCH', {
                                      title: r.title,
                                      description: r.description,
                                      icon: r.icon,
                                      cost: r.cost,
                                      active: !r.active,
                                    }),
                                  r.active ? '奖励已下架' : '奖励已上架',
                                )
                              }
                            >
                              {r.active ? '下架奖励' : '重新上架'}
                            </Button>
                          )}
                        </article>
                      );
                    })}
                </div>
                {!data.rewards.filter(
                  (r) =>
                    !isParent ||
                    rewardFilter === 'all' ||
                    (rewardFilter === 'active' ? !!r.active : !r.active),
                ).length && (
                  <Empty>
                    {isParent
                      ? '当前筛选下没有奖励，可以添加奖励或切换状态查看。'
                      : '小铺还在准备中，家长可以添加第一个奖励。'}
                  </Empty>
                )}
                <div className="card redemption-list">
                  <h3>我的兑换记录</h3>
                  {data.redemptions.length ? (
                    data.redemptions.slice(0, 10).map((r) => (
                      <div className="list-row" key={r.id}>
                        <Tile icon={r.icon} />
                        <div>
                          <strong>{r.title}</strong>
                          <p>
                            {r.cost} 星 · {timeText(r.created_at)}
                            {r.review_note && ` · ${r.review_note}`}
                          </p>
                        </div>
                        <span className={`tag ${r.status}`}>
                          {r.status === 'pending'
                            ? '等待确认'
                            : r.status === 'approved'
                              ? '兑换成功'
                              : '已退回'}
                        </span>
                      </div>
                    ))
                  ) : (
                    <p className="muted">还没有兑换记录，先来积攒小星星吧。</p>
                  )}
                </div>
              </section>
            )}
            {page === 'review' && isParent && (
              <section>
                <PageHeading
                  eyebrow="EVERY EFFORT DESERVES TO BE SEEN"
                  title="看看孩子的努力"
                  icon={<ShieldCheck />}
                />
                <div className="review-layout">
                  <div className="card">
                    <h3>
                      任务待确认 <b className="count">{data.reviews.length}</b>
                    </h3>
                    {data.reviews.length ? (
                      data.reviews.map((s) => (
                        <article className="review-card" key={s.id}>
                          <div className="list-row">
                            <Tile icon={s.icon} subject={s.subject} />
                            <div>
                              <h4>
                                {subjects[s.subject] || '其他'} · {s.title}
                              </h4>
                              <p>
                                任务日期：{s.date} · 每次 {s.stars} 星 · 每日上限 {s.daily_limit} 次
                              </p>
                              <p className="review-detail">
                                任务说明：{s.description || '未填写任务说明'}
                              </p>
                              {s.note && <p className="review-detail">提交备注：{s.note}</p>}
                            </div>
                          </div>
                          <ReviewAward
                            stars={s.stars}
                            limit={s.remaining}
                            busy={busy}
                            approve={(award) => review(s.id, true, 'submissions', award)}
                            reject={() => review(s.id, false)}
                          />
                        </article>
                      ))
                    ) : (
                      <Empty>暂时没有待确认任务，孩子的每次努力都会出现在这里。</Empty>
                    )}
                  </div>
                  <div className="card">
                    <h3>
                      兑换待确认{' '}
                      <b className="count">
                        {data.redemptions.filter((r) => r.status === 'pending').length}
                      </b>
                    </h3>
                    {data.redemptions
                      .filter((r) => r.status === 'pending')
                      .map((r) => (
                        <article className="review-card" key={r.id}>
                          <div className="list-row">
                            <Tile icon={r.icon} />
                            <div>
                              <h4>{r.title}</h4>
                              <p>
                                兑换消耗 {r.cost} 星 · 当前余额 {data.wallet.balance}
                              </p>
                            </div>
                          </div>
                          <div className="review-actions">
                            <Button
                              className="secondary"
                              disabled={busy}
                              onClick={() => review(r.id, false, 'redemptions')}
                            >
                              退回
                            </Button>
                            <Button
                              className="primary"
                              disabled={busy}
                              onClick={() => review(r.id, true, 'redemptions')}
                            >
                              确认兑换
                            </Button>
                          </div>
                        </article>
                      ))}
                    {!data.redemptions.some((r) => r.status === 'pending') && (
                      <Empty>还没有新的兑换心愿。</Empty>
                    )}
                  </div>
                </div>
              </section>
            )}
            {page === 'rules' && isParent && (
              <section>
                <PageHeading
                  eyebrow="SMALL ROUTINES, GROWING EVERY DAY"
                  title="任务规则"
                  icon={<ClipboardList />}
                  action={
                    <Button className="primary" onClick={() => setDialog({ type: 'rule' })}>
                      <Plus size={17} /> 添加任务
                    </Button>
                  }
                />
                <div className="info-strip">
                  <ShieldCheck size={19} />
                  <span>
                    模板修改从明天起生效，已有的每日任务快照保留。当天调整请在任务详情中编辑。
                  </span>
                </div>
                {subjectPicker}
                <div className="rules-grid">
                  {data.rules
                    .filter((r) => r.subject === subject)
                    .map((r) => (
                      <article className="card rule-card" key={r.id}>
                        <div className="list-row">
                          <Tile icon={r.icon} subject={r.subject} />
                          <div>
                            <h3>
                              {subjects[r.subject]} · {r.title}
                            </h3>
                            <p>
                              每次 {r.stars} 星 · 每日上限 {r.daily_limit} 次
                            </p>
                          </div>
                          <Button
                            className="icon-btn"
                            aria-label={`编辑${r.title}`}
                            onClick={() => setDialog({ type: 'rule', rule: r })}
                          >
                            <Pencil size={18} />
                          </Button>
                        </div>
                        <p className="rule-description">{r.description || '暂无补充说明'}</p>
                        <div className="rule-meta">
                          <span>
                            <CalendarDays size={15} />
                            {r.schedule === 'daily'
                              ? '每天'
                              : r.schedule === 'once'
                                ? r.on_date
                                : `每周 ${r.weekdays.map((n) => weekdays[n]).join('、')}`}
                          </span>
                          <span className={`tag ${r.enabled ? 'approved' : 'rejected'}`}>
                            {r.enabled ? '已启用' : '已停用'}
                          </span>
                        </div>
                        <div className="review-actions">
                          {(['up', 'down'] as const).map((direction) => (
                            <Button
                              key={direction}
                              className="secondary"
                              disabled={
                                busy ||
                                (direction === 'up'
                                  ? data.rules.filter((x) => x.subject === r.subject)[0]?.id ===
                                    r.id
                                  : data.rules.filter((x) => x.subject === r.subject).at(-1)?.id ===
                                    r.id)
                              }
                              onClick={() =>
                                act(
                                  () =>
                                    api(`/api/children/${childId}/task-order`, 'POST', {
                                      rule_key: r.rule_key,
                                      direction,
                                    }),
                                  '任务顺序已保存',
                                )
                              }
                            >
                              {direction === 'up' ? '上移' : '下移'}
                            </Button>
                          ))}
                        </div>
                        <small>
                          版本 {r.version} · {r.effective_from} 起生效
                        </small>
                      </article>
                    ))}
                </div>
                {!data.rules.length && <Empty>从一个容易坚持的小任务开始吧。</Empty>}
              </section>
            )}
          </>
        )}
      </main>
      <AnimatePresence>
        {skinOpen && (
          <Modal
            title={user.role === 'admin' ? '界面主题' : '我的换装间'}
            close={() => setSkinOpen(false)}
          >
            <div className="skin-options">
              {SKINS.map(({ key, label: name, asset }) => (
                <Button
                  key={key}
                  className={key === skin ? 'chosen' : ''}
                  aria-pressed={key === skin}
                  onClick={() => {
                    setSkin(key);
                    setSkinOpen(false);
                  }}
                >
                  <img src={`/assets/${asset}.webp`} alt={name} />
                  <strong>{name}</strong>
                  <span>{key === skin ? '正在使用' : '使用这套皮肤'}</span>
                </Button>
              ))}
            </div>
          </Modal>
        )}
      </AnimatePresence>
      <Toast message={notice} dismiss={() => setNotice('')} />
      <AnimatePresence>
        {dialog && (
          <Modal
            title={
              dialog.type === 'task'
                ? dialog.task.title
                : dialog.type === 'rule'
                  ? dialog.task
                    ? '编辑单日任务'
                    : dialog.rule
                      ? '编辑任务模板'
                      : '添加任务模板'
                  : dialog.type === 'reward'
                    ? dialog.reward
                      ? '编辑奖励'
                      : '添加奖励'
                    : dialog.type === 'redeem'
                      ? '兑换一个小心愿'
                      : dialog.type === 'entry'
                        ? '记一笔星星'
                        : dialog.type === 'reverse'
                          ? '撤销这笔流水'
                          : dialog.type === 'settings'
                            ? '账号与设置'
                            : dialog.title
            }
            close={() => {
              if (!busy) setDialog(null);
            }}
          >
            {dialog.type === 'task' && (
              <TaskDetail
                task={dialog.task}
                parent={!!isParent}
                busy={busy}
                today={data?.today || currentDate()}
                submit={(note, stars, quantity) => submitTask(dialog.task, note, stars, quantity)}
                edit={() => setDialog({ type: 'rule', task: dialog.task })}
              />
            )}
            {dialog.type === 'rule' && (
              <RuleForm
                rule={dialog.rule}
                task={dialog.task}
                busy={busy}
                save={async (v) => {
                  const d = dialog;
                  const ok = await act(
                    () =>
                      api(
                        d.task
                          ? `/api/tasks/${d.task.id}`
                          : d.rule
                            ? `/api/rules/${d.rule.rule_key}`
                            : `/api/children/${childId}/rules`,
                        d.task || d.rule ? 'PATCH' : 'POST',
                        v,
                      ),
                    '规则已保存',
                  );
                  if (ok) setDialog(null);
                }}
              />
            )}
            {dialog.type === 'reward' && (
              <RewardForm
                reward={dialog.reward}
                busy={busy}
                save={async (v) => {
                  const r = dialog.reward;
                  const ok = await act(
                    () =>
                      api(
                        r ? `/api/rewards/${r.id}` : `/api/children/${childId}/rewards`,
                        r ? 'PATCH' : 'POST',
                        v,
                      ),
                    '奖励已保存',
                  );
                  if (ok) setDialog(null);
                }}
              />
            )}
            {dialog.type === 'redeem' && (
              <div className="redeem-confirm">
                <Tile icon={dialog.reward.icon} size="large" />
                <h3>{dialog.reward.title}</h3>
                <p>
                  家长确认后会消耗 <b>{dialog.reward.cost}</b> 颗星星。
                </p>
                <p className="muted">
                  当前余额 {data?.wallet.balance} 星，确认后剩余{' '}
                  {(data?.wallet.balance || 0) - dialog.reward.cost} 星。
                </p>
                <Button
                  className="primary full"
                  loading={busy}
                  onClick={async () => {
                    const ok = await act(
                      () =>
                        api(
                          `/api/children/${childId}/redemptions`,
                          'POST',
                          { reward_id: dialog.reward.id },
                          requestId(),
                        ),
                      '心愿已送达，等家长确认吧',
                    );
                    if (ok) {
                      feedback();
                      setDialog(null);
                    }
                  }}
                >
                  请家长帮我兑换
                </Button>
              </div>
            )}
            {dialog.type === 'entry' && (
              <EntryForm
                busy={busy}
                save={async (v) => {
                  const ok = await act(
                    () => api(`/api/children/${childId}/ledger`, 'POST', v, requestId()),
                    '星星流水已记录',
                  );
                  if (ok) setDialog(null);
                }}
              />
            )}
            {dialog.type === 'reverse' && (
              <form
                onSubmit={async (e) => {
                  e.preventDefault();
                  const note = String(new FormData(e.currentTarget).get('note'));
                  const ok = await act(
                    () => api(`/api/ledger/${dialog.entry.id}/reverse`, 'POST', { note }),
                    '已追加反向流水',
                  );
                  if (ok) setDialog(null);
                }}
              >
                <p>
                  撤销「{dialog.entry.title}」，将{dialog.entry.amount > 0 ? '扣回' : '返还'}{' '}
                  {Math.abs(dialog.entry.amount)} 星。原记录会保留。
                </p>
                <Field label="撤销原因">
                  <input name="note" required maxLength={100} />
                </Field>
                <Button className="primary full" loading={busy}>
                  确认撤销
                </Button>
              </form>
            )}
            {dialog.type === 'settings' && (
              <SettingsPanel user={user} logout={logout} busy={busy} act={act} />
            )}
            {dialog.type === 'celebrate' && (
              <div className="celebration">
                <div className="celebration-star">
                  <StarIcon size={95} />
                  <span>✧</span>
                  <i>✦</i>
                </div>
                <h3>{dialog.title}</h3>
                <p>{dialog.text}</p>
                <Button className="primary full" onClick={() => setDialog(null)}>
                  继续我的探险 <ChevronRight size={18} />
                </Button>
              </div>
            )}
          </Modal>
        )}
      </AnimatePresence>
    </InteractionSurface>
  );
}
function PageHeading({
  eyebrow,
  title,
  icon,
  action,
}: {
  eyebrow: string;
  title: string;
  icon: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        <span className="eyebrow">{eyebrow}</span>
        <h2>
          {icon}
          {title}
        </h2>
      </div>
      {action}
    </div>
  );
}
function DateStrip({
  date,
  today,
  setDate,
}: {
  date: string;
  today: string;
  setDate: (s: string) => void;
}) {
  return (
    <div className="date-strip" role="group" aria-label="选择任务日期">
      <Button className="date-arrow" aria-label="前一周" onClick={() => setDate(shift(date, -7))}>
        <ChevronLeft size={16} />
      </Button>
      {Array.from({ length: 7 }, (_, i) =>
        shift(date, i - ((new Date(date + 'T12:00:00Z').getUTCDay() + 6) % 7)),
      ).map((d) => (
        <Button
          key={d}
          type="button"
          onClick={() => setDate(d)}
          className={date === d ? 'selected' : ''}
          aria-label={dateText(d)}
          aria-pressed={date === d}
          aria-current={d === today ? 'date' : undefined}
        >
          <span>
            {d === today ? '今天' : '周' + weekdays[new Date(d + 'T12:00:00Z').getUTCDay()]}
          </span>
          <strong>
            {Number(d.slice(5, 7))}.{Number(d.slice(8))}
          </strong>
        </Button>
      ))}
      <Button className="date-arrow" aria-label="后一周" onClick={() => setDate(shift(date, 7))}>
        <ChevronRight size={16} />
      </Button>
    </div>
  );
}
function Ledger({
  entries,
  isParent,
  reverse,
}: {
  entries: Entry[];
  isParent: boolean;
  reverse: (e: Entry) => void;
}) {
  const [filter, setFilter] = useState('all'),
    [day, setDay] = useState('');
  const rows = entries.filter(
    (e) =>
      (filter === 'all' || (filter === 'in' ? e.amount > 0 : e.amount < 0)) &&
      (!day || e.date === day),
  );
  return (
    <div className="card ledger">
      <div className="section-line">
        <h3>
          <StarIcon size={20} /> 星星明细
        </h3>
        <select aria-label="收支筛选" value={filter} onChange={(e) => setFilter(e.target.value)}>
          <option value="all">全部收支</option>
          <option value="in">获得的星星</option>
          <option value="out">消耗的星星</option>
        </select>
      </div>
      <div className="ledger-date">
        <input
          aria-label="流水日期"
          type="date"
          value={day}
          onChange={(e) => setDay(e.target.value)}
        />
        {day && (
          <Button className="text-btn" onClick={() => setDay('')}>
            查看全部
          </Button>
        )}
      </div>
      {rows.length ? (
        rows.map((e) => (
          <div className="ledger-row" key={e.id}>
            <div className={`ledger-icon ${e.amount < 0 ? 'out' : ''}`}>
              {e.amount > 0 ? <ArrowDownLeft /> : <ArrowUpRight />}
            </div>
            <div className="ledger-detail">
              <strong>{e.title}</strong>
              <p>
                {e.date} ·{' '}
                {e.note ||
                  {
                    task: '完成任务获得',
                    bonus: '家长奖励',
                    spend: '购买消费',
                    deduction: '行为扣星',
                    reward: '奖励兑换',
                    reversal: '撤销调整',
                  }[e.kind]}
              </p>
              {e.reversed_by && <small>已撤销 · 原记录保留</small>}
            </div>
            <div className="entry-amount">
              <strong className={e.amount > 0 ? 'positive' : 'negative'}>
                {e.amount > 0 ? '+' : ''}
                {e.amount}
                <StarIcon size={15} />
              </strong>
              {isParent && e.kind !== 'reversal' && !e.reversed_by && (
                <Button className="text-btn" onClick={() => reverse(e)}>
                  撤销
                </Button>
              )}
            </div>
          </div>
        ))
      ) : (
        <Empty>这一天还没有星星记录。</Empty>
      )}
      <p className="footnote">显示最近 1000 笔流水 · 撤销通过反向流水记录</p>
    </div>
  );
}
function AwardFields({
  stars,
  quantity,
  limit,
  setStars,
  setQuantity,
}: {
  stars: number;
  quantity: number;
  limit: number;
  setStars: (n: number) => void;
  setQuantity: (n: number) => void;
}) {
  return (
    <>
      <div className="form-row">
        <Field label="每次发放星星">
          <input
            type="number"
            min={1}
            max={101}
            required
            value={stars}
            onChange={(e) => setStars(Number(e.target.value))}
          />
        </Field>
        <Field label="本次完成次数">
          <input
            type="number"
            min={1}
            max={limit}
            required
            value={quantity}
            onChange={(e) => setQuantity(Number(e.target.value))}
          />
        </Field>
      </div>
      <p>合计发放 {stars * quantity} 颗星星</p>
    </>
  );
}
function ReviewAward({
  stars,
  limit,
  busy,
  approve,
  reject,
}: {
  stars: number;
  limit: number;
  busy: boolean;
  approve: (a: { unit_stars: number; quantity: number }) => void;
  reject: () => void;
}) {
  const [unitStars, setStars] = useState(stars),
    [quantity, setQuantity] = useState(1);
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        approve({ unit_stars: unitStars, quantity });
      }}
    >
      <AwardFields
        stars={unitStars}
        quantity={quantity}
        limit={limit}
        setStars={setStars}
        setQuantity={setQuantity}
      />
      <div className="review-actions">
        <Button type="button" className="secondary" disabled={busy} onClick={reject}>
          退回
        </Button>
        <Button className="primary" disabled={busy}>
          确认 {quantity} 次，发放 {unitStars * quantity} 星
        </Button>
      </div>
    </form>
  );
}

function TaskDetail({
  task: t,
  parent,
  busy,
  today,
  submit,
  edit,
}: {
  task: Task;
  parent: boolean;
  busy: boolean;
  today: string;
  submit: (n: string, stars: number, quantity: number) => void;
  edit: () => void;
}) {
  const [note, setNote] = useState('');
  const [unitStars, setUnitStars] = useState(t.stars);
  const [quantity, setQuantity] = useState(1);
  return (
    <div>
      <div className="detail-hero">
        <Tile icon={t.icon} subject={t.subject} />
        <div>
          <h3>{t.title}</h3>
          <p>
            每次 {t.stars} 星 · 每日上限 {t.daily_limit} 次
          </p>
        </div>
      </div>
      <p className="detail-description">
        {t.description || '认真完成这件小事，收获属于你的星星。'}
      </p>
      <div className="info-strip">
        <ShieldCheck size={18} />
        <span>
          {t.date} 的任务规则 · 模板版本 {t.rule_version}
          <br />
          已通过 {t.approved} 次 · 待确认 {t.pending} 次
        </span>
      </div>
      {t.submissions
        .filter((s) => s.status === 'rejected')
        .map((s) => (
          <p className="review-note" key={s.id}>
            家长留言：{s.review_note}
          </p>
        ))}
      {(parent || t.date === today) &&
        t.date <= today &&
        t.approved + t.pending < t.daily_limit && (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              submit(note, unitStars, quantity);
            }}
          >
            {parent && (
              <Field label="想告诉家长的话（可选）">
                <textarea
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                  maxLength={1000}
                  placeholder="例如：今天我读了两页绘本！"
                />
              </Field>
            )}
            {parent && (
              <AwardFields
                stars={unitStars}
                quantity={quantity}
                limit={t.daily_limit - t.approved - t.pending}
                setStars={setUnitStars}
                setQuantity={setQuantity}
              />
            )}
            <Button className="primary full" loading={busy}>
              {parent
                ? `代完成 ${quantity} 次，发放 ${unitStars * quantity} 星`
                : '我完成了 1 次，请家长确认'}
            </Button>
          </form>
        )}
      {parent && t.date >= today && !t.submissions.length && (
        <Button className="text-btn full" onClick={edit}>
          <Pencil size={15} /> 编辑这一天的任务规则
        </Button>
      )}
    </div>
  );
}
function RuleForm({
  rule,
  task,
  busy,
  save,
}: {
  rule?: Rule;
  task?: Task;
  busy: boolean;
  save: (v: unknown) => void;
}) {
  const [presetIndex, setPresetIndex] = useState(-1);
  const initial = rule || task || (presetIndex >= 0 ? presets[presetIndex] : undefined);
  const [schedule, setSchedule] = useState(rule?.schedule || 'daily'),
    [days, setDays] = useState<number[]>(rule?.weekdays || [1, 2, 3, 4, 5]);
  return (
    <>
      {!rule && !task && (
        <Field label="选择预制模板（可修改）">
          <select value={presetIndex} onChange={(e) => setPresetIndex(Number(e.target.value))}>
            <option value={-1}>自己创建子任务</option>
            {Object.entries(subjects).map(([k, v]) => (
              <optgroup key={k} label={v}>
                {presets.map((p, i) =>
                  p.subject === k ? (
                    <option key={i} value={i}>
                      {p.title}
                    </option>
                  ) : null,
                )}
              </optgroup>
            ))}
          </select>
        </Field>
      )}
      <form
        key={presetIndex}
        onSubmit={(e) => {
          e.preventDefault();
          const f = new FormData(e.currentTarget);
          save({
            title: f.get('title'),
            subject: f.get('subject'),
            description: f.get('description'),
            icon: f.get('icon'),
            stars: Number(f.get('stars')),
            daily_limit: Number(f.get('daily_limit')),
            ...(!task
              ? {
                  schedule,
                  weekdays: days,
                  on_date: schedule === 'once' ? f.get('on_date') : null,
                  enabled: f.get('enabled') === 'on',
                }
              : {}),
          });
        }}
      >
        <Field label="一级科目">
          <select name="subject" defaultValue={initial?.subject || 'chinese'}>
            {Object.entries(subjects).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </Field>
        <Field label="任务名称">
          <input
            name="title"
            defaultValue={initial?.title}
            required
            maxLength={100}
            placeholder="例如：读英文绘本"
          />
        </Field>
        <Field label="给孩子的具体说明">
          <textarea
            name="description"
            defaultValue={initial?.description}
            maxLength={1000}
            placeholder="怎样才算完成？写清楚，孩子更容易坚持。"
          />
        </Field>
        <div className="form-row">
          <Field label="每次获得星星">
            <input
              type="number"
              name="stars"
              min={1}
              max={100}
              defaultValue={initial?.stars || 1}
              required
            />
          </Field>
          <Field label="每天最多完成次数">
            <input
              type="number"
              name="daily_limit"
              min={1}
              max={20}
              defaultValue={initial?.daily_limit || 1}
              required
            />
          </Field>
        </div>
        <p>家长确认时可调整本次星星和完成次数。</p>
        <Field label="任务图标">
          <select name="icon" defaultValue={initial?.icon || 'book'}>
            {Object.entries({
              book: '📖 阅读',
              brush: '🪥 清洁',
              blocks: '🧩 整理',
              leaf: '🌿 运动',
              bed: '🌙 睡眠',
              pencil: '✏️ 学习',
              heart: '❤️ 关爱',
            }).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </Field>
        {!task && (
          <>
            <Field label="重复安排">
              <select
                value={schedule}
                onChange={(e) => setSchedule(e.target.value as Rule['schedule'])}
              >
                <option value="daily">每天</option>
                <option value="weekly">每周指定日</option>
                <option value="once">指定日期（一次）</option>
              </select>
            </Field>
            {schedule === 'weekly' && (
              <div className="weekday-picks">
                {[1, 2, 3, 4, 5, 6, 0].map((n) => (
                  <Button
                    type="button"
                    className={days.includes(n) ? 'selected' : ''}
                    aria-pressed={days.includes(n)}
                    key={n}
                    onClick={() =>
                      setDays(days.includes(n) ? days.filter((x) => x !== n) : [...days, n])
                    }
                  >
                    周{weekdays[n]}
                  </Button>
                ))}
              </div>
            )}
            {schedule === 'once' && (
              <Field label="任务日期">
                <input
                  type="date"
                  name="on_date"
                  defaultValue={rule?.on_date || currentDate()}
                  min={rule ? shift(currentDate(), 1) : currentDate()}
                  required
                />
              </Field>
            )}
            <label className="check-field">
              <input type="checkbox" name="enabled" defaultChecked={rule ? !!rule.enabled : true} />{' '}
              启用这个任务模板
            </label>
            <p className="muted">
              {rule
                ? '修改从明天生效，不改变已生成的每日任务。'
                : '新任务从今天开始安排，孩子打开即可看到。'}
            </p>
          </>
        )}
        <Button className="primary full" loading={busy}>
          保存任务规则
        </Button>
      </form>
    </>
  );
}
function RewardForm({
  reward,
  busy,
  save,
}: {
  reward?: Reward;
  busy: boolean;
  save: (v: unknown) => void;
}) {
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        const f = new FormData(e.currentTarget);
        save({
          title: f.get('title'),
          description: f.get('description'),
          cost: Number(f.get('cost')),
          icon: f.get('icon'),
          active: f.get('active') === 'on',
        });
      }}
    >
      <Field label="奖励名称">
        <input name="title" defaultValue={reward?.title} required maxLength={100} />
      </Field>
      <Field label="奖励说明">
        <textarea name="description" defaultValue={reward?.description} maxLength={1000} />
      </Field>
      <div className="form-row">
        <Field label="兑换所需星星">
          <input
            type="number"
            name="cost"
            min={1}
            max={100000}
            defaultValue={reward?.cost || 5}
            required
          />
        </Field>
        <Field label="图标">
          <select name="icon" defaultValue={reward?.icon || 'gift'}>
            {Object.entries({
              icecream: '冰淇淋',
              toy: '玩具',
              movie: '电影',
              picnic: '野餐',
              gift: '礼物',
            }).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <label className="check-field">
        <input type="checkbox" name="active" defaultChecked={reward ? !!reward.active : true} />{' '}
        在奖励小铺上架
      </label>
      <Button className="primary full" loading={busy}>
        保存奖励
      </Button>
    </form>
  );
}
function EntryForm({ busy, save }: { busy: boolean; save: (v: unknown) => void }) {
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        const f = new FormData(e.currentTarget);
        save({
          title: f.get('title'),
          note: f.get('note'),
          amount: Number(f.get('amount')),
          kind: f.get('kind'),
        });
      }}
    >
      <Field label="记账类型">
        <select name="kind">
          <option value="spend">购买消费（扣星）</option>
          <option value="bonus">额外奖励（加星）</option>
          <option value="deduction">行为扣星</option>
        </select>
      </Field>
      <Field label="原因">
        <input name="title" placeholder="例如：买冰淇淋" required maxLength={100} />
      </Field>
      <Field label="星星数量">
        <input type="number" name="amount" min={1} max={100000} defaultValue={1} required />
      </Field>
      <Field label="补充说明">
        <textarea name="note" maxLength={1000} />
      </Field>
      <Button className="primary full" loading={busy}>
        确认记账
      </Button>
    </form>
  );
}
function SettingsPanel({
  user,
  logout,
  busy,
  act,
}: {
  user: User;
  logout: () => Promise<void>;
  busy: boolean;
  act: (fn: () => Promise<unknown>, message?: string) => Promise<boolean>;
}) {
  const [change, setChange] = useState(false);
  return (
    <div>
      <div className="account-info">
        <div className="avatar">{user.role === 'child' ? '🦖' : '🌿'}</div>
        <div>
          <h3>{user.name}</h3>
          <p>
            {user.username} ·{' '}
            {user.role === 'child' ? '小朋友' : user.role === 'parent' ? '家长' : '超级管理员'}
          </p>
        </div>
      </div>
      <Button className="settings-row" onClick={() => setChange(!change)}>
        <LockKeyhole size={20} /> 修改密码
        <ChevronRight size={18} />
      </Button>
      {change && (
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            const ok = await act(() =>
              api('/api/password', 'POST', {
                current: f.get('current'),
                password: f.get('password'),
              }),
            );
            if (ok) await logout();
          }}
        >
          <Field label="原密码">
            <input type="password" name="current" required autoComplete="current-password" />
          </Field>
          <Field label="新密码（至少 8 位）">
            <input
              type="password"
              name="password"
              minLength={8}
              maxLength={200}
              required
              autoComplete="new-password"
            />
          </Field>
          <Button className="primary full" loading={busy}>
            保存并重新登录
          </Button>
        </form>
      )}
      <div className="info-strip">
        <ShieldCheck size={19} />
        <span>
          {user.role === 'admin'
            ? '在管理中心分配家庭与账号，在这里修改自己的登录密码。'
            : '家长入口需要家长账号登录。星星到账与消费都由家长确认。'}
        </span>
      </div>
      <Button className="secondary full" onClick={logout}>
        <LogOut size={18} /> 退出 / 更换账号
      </Button>
      <p className="footnote">星星探险家 v0.1 · 日期按北京时间计算</p>
    </div>
  );
}
function AdminPanel({
  busy,
  act,
  logout,
}: {
  busy: boolean;
  act: (fn: () => Promise<unknown>, message?: string) => Promise<boolean>;
  logout: () => Promise<void>;
}) {
  const [accounts, setAccounts] = useState<{
      families: { id: string; name: string }[];
      users: User[];
    }>({ families: [], users: [] }),
    [loadingAccounts, setLoadingAccounts] = useState(true),
    [query, setQuery] = useState(''),
    [roleFilter, setRoleFilter] = useState('all'),
    [familyFilter, setFamilyFilter] = useState('all'),
    [statusFilter, setStatusFilter] = useState('all'),
    [selectedFamily, setSelectedFamily] = useState(''),
    [pendingAction, setPendingAction] = useState(''),
    [backup, setBackup] = useState<unknown>(null),
    [fileName, setFileName] = useState(''),
    [error, setError] = useState(''),
    [fileError, setFileError] = useState('');
  const accountForm = useRef<HTMLFormElement>(null);
  const fileRead = useRef(0);
  const load = useCallback(async () => {
    setLoadingAccounts(true);
    try {
      setAccounts(await api<typeof accounts>('/api/admin/accounts'));
      setError('');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoadingAccounts(false);
    }
  }, []);
  useEffect(() => {
    load();
  }, [load]);
  async function perform(key: string, fn: () => Promise<unknown>, message: string) {
    if (busy || pendingAction) return false;
    setPendingAction(key);
    try {
      return await act(fn, message);
    } finally {
      setPendingAction('');
    }
  }
  const familyNames = new Map(accounts.families.map((family) => [family.id, family.name]));
  const roleNames = { child: '小朋友', parent: '家长', admin: '管理员' };
  const normalizedQuery = query.trim().toLocaleLowerCase();
  const filteredAccounts = accounts.users.filter(
    (user) =>
      (roleFilter === 'all' || user.role === roleFilter) &&
      (familyFilter === 'all' || user.family_id === familyFilter) &&
      (statusFilter === 'all' || Boolean(user.active) === (statusFilter === 'active')) &&
      [user.name, user.username, familyNames.get(user.family_id || '') || '全局管理'].some(
        (value) => value.toLocaleLowerCase().includes(normalizedQuery),
      ),
  );
  const hasFilters =
    !!query || roleFilter !== 'all' || familyFilter !== 'all' || statusFilter !== 'all';
  function clearFilters() {
    setQuery('');
    setRoleFilter('all');
    setFamilyFilter('all');
    setStatusFilter('all');
  }
  const summary = [
    { label: '家庭', value: accounts.families.length, icon: Building2 },
    { label: '家长', value: accounts.users.filter((u) => u.role === 'parent').length, icon: Users },
    {
      label: '小朋友',
      value: accounts.users.filter((u) => u.role === 'child').length,
      icon: Sparkles,
    },
    { label: '已停用', value: accounts.users.filter((u) => !u.active).length, icon: UserRoundX },
  ];
  return (
    <section className="admin-page">
      <div className="admin-heading">
        <div>
          <span className="eyebrow">FAMILY MANAGEMENT</span>
          <h2>家庭与数据管理</h2>
          <p className="muted">照顾好每一个家庭，让孩子的成长记录有序留存。</p>
        </div>
        <Button
          className="primary"
          onClick={() => {
            const target = accountForm.current?.querySelector<HTMLInputElement>('input[name=name]');
            target?.scrollIntoView({ block: 'center' });
            target?.focus({ preventScroll: true });
          }}
        >
          <Plus size={18} /> 添加账号
        </Button>
      </div>
      <div className="admin-overview" aria-label="家庭与账号概览" aria-busy={loadingAccounts}>
        {summary.map(({ label, value, icon: Icon }) => (
          <div className="admin-stat" key={label}>
            <div className="admin-stat-icon">
              <Icon size={22} />
            </div>
            <div>
              <strong>{loadingAccounts ? '—' : value}</strong>
              <span>{label}</span>
            </div>
          </div>
        ))}
      </div>
      {error && (
        <div className="error global-error" role="alert">
          {error}
          <Button className="text-btn" onClick={load}>
            重新加载账号
          </Button>
        </div>
      )}
      <div className="admin-layout">
        <section
          className="card account-list admin-accounts"
          aria-labelledby="admin-accounts-title"
        >
          <div className="admin-section-heading">
            <div>
              <h3 id="admin-accounts-title">
                已分配账号 <span className="admin-count">{accounts.users.length}</span>
              </h3>
              <p>按家庭和身份查找，随时管理账号状态。</p>
            </div>
            <Button
              className="icon-btn"
              aria-label="刷新账号列表"
              disabled={loadingAccounts || busy}
              loading={loadingAccounts}
              onClick={load}
            >
              <RefreshCw size={17} />
            </Button>
          </div>
          <div className="admin-toolbar">
            <div className="admin-search">
              <Search size={18} />
              <input
                type="search"
                aria-label="搜索账号"
                placeholder="搜索称呼、账号或家庭"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
              />
            </div>
            <select
              aria-label="筛选家庭"
              value={familyFilter}
              onChange={(e) => setFamilyFilter(e.target.value)}
            >
              <option value="all">全部家庭</option>
              {accounts.families.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.name}
                </option>
              ))}
            </select>
            <select
              aria-label="筛选身份"
              value={roleFilter}
              onChange={(e) => setRoleFilter(e.target.value)}
            >
              <option value="all">全部身份</option>
              <option value="parent">家长</option>
              <option value="child">小朋友</option>
              <option value="admin">管理员</option>
            </select>
            <select
              aria-label="账号状态"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
            >
              <option value="all">全部状态</option>
              <option value="active">使用中</option>
              <option value="inactive">已停用</option>
            </select>
          </div>
          <div className="admin-result-line">
            <span role="status">
              {loadingAccounts ? '正在加载账号…' : `显示 ${filteredAccounts.length} 个账号`}
            </span>
            {hasFilters && (
              <Button className="text-btn" onClick={clearFilters}>
                清除筛选
              </Button>
            )}
          </div>
          <div className="admin-account-head" aria-hidden="true">
            <span>称呼 / 登录账号</span>
            <span>所属家庭</span>
            <span>身份</span>
            <span>状态</span>
            <span>操作</span>
          </div>
          <div className="admin-account-rows" aria-busy={loadingAccounts}>
            {filteredAccounts.map((u) => (
              <div className="list-row admin-account-row" key={u.id}>
                <div className="admin-account-identity">
                  <div className="avatar" aria-hidden="true">
                    {u.role === 'child' ? '🦖' : u.role === 'parent' ? '🌿' : '🛡️'}
                  </div>
                  <div>
                    <strong>{u.name}</strong>
                    <small>{u.username}</small>
                  </div>
                </div>
                <div className="admin-account-family" data-label="家庭">
                  {familyNames.get(u.family_id || '') || '全局管理'}
                </div>
                <div data-label="身份">
                  <span className="admin-role">{roleNames[u.role]}</span>
                </div>
                <div data-label="状态">
                  <span className="admin-state" data-active={!!u.active}>
                    {u.active ? '使用中' : '已停用'}
                  </span>
                </div>
                <div className="account-controls">
                  {u.role !== 'admin' ? (
                    <>
                      <Button
                        className="text-btn"
                        disabled={busy}
                        loading={pendingAction === u.id}
                        onClick={async () => {
                          if (
                            await perform(
                              u.id,
                              () => api(`/api/admin/users/${u.id}`, 'PATCH', { active: !u.active }),
                              '账号状态已更新',
                            )
                          )
                            await load();
                        }}
                      >
                        {u.active ? '停用' : '启用'}
                      </Button>
                      <ResetPassword user={u} busy={busy} act={act} />
                    </>
                  ) : (
                    <span className="muted">在设置中管理</span>
                  )}
                </div>
              </div>
            ))}
            {!filteredAccounts.length && !loadingAccounts && (
              <div className="admin-account-empty">
                <Search size={28} />
                <strong>{hasFilters ? '没有找到匹配的账号' : '还没有分配账号'}</strong>
                <p>
                  {hasFilters
                    ? '试试其他关键词，或清除上方筛选。'
                    : '先创建家庭，再为家长和孩子分配账号。'}
                </p>
              </div>
            )}
          </div>
        </section>
        <div className="admin-setup">
          <section className="card admin-create-family">
            <div className="admin-section-heading">
              <div>
                <h3>
                  <span className="admin-step">01</span> 创建家庭
                </h3>
                <p>为家长和孩子准备一个共同的空间。</p>
              </div>
            </div>
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                const form = e.currentTarget;
                const name = new FormData(form).get('name');
                let family: { id: string; name: string } | undefined;
                if (
                  await perform(
                    'family',
                    async () => {
                      family = await api('/api/admin/families', 'POST', { name });
                    },
                    '家庭已创建',
                  )
                ) {
                  form.reset();
                  const created = family;
                  if (created) {
                    setAccounts((current) => ({
                      ...current,
                      families: current.families.some((f) => f.id === created.id)
                        ? current.families
                        : [...current.families, created],
                    }));
                    setSelectedFamily(created.id);
                  }
                  await load();
                }
              }}
            >
              <Field label="家庭名称">
                <input name="name" placeholder="例如：小星一家" required maxLength={100} />
              </Field>
              <Button
                className="secondary full"
                disabled={busy}
                loading={pendingAction === 'family'}
              >
                <Plus size={17} /> 创建家庭
              </Button>
            </form>
            <div className="family-tags" aria-label="已创建的家庭">
              {accounts.families.map((f) => (
                <span className="tag approved" key={f.id}>
                  {f.name}
                </span>
              ))}
            </div>
          </section>
          <section className="card admin-create-account">
            <div className="admin-section-heading">
              <div>
                <h3>
                  <span className="admin-step">02</span> 预分配账号
                </h3>
                <p>分配后即可登录，无需自行注册。</p>
              </div>
            </div>
            <form
              ref={accountForm}
              onSubmit={async (e) => {
                e.preventDefault();
                const form = e.currentTarget,
                  f = new FormData(form);
                if (
                  await perform(
                    'account',
                    () => api('/api/admin/users', 'POST', Object.fromEntries(f)),
                    '账号已分配',
                  )
                ) {
                  form.reset();
                  clearFilters();
                  await load();
                }
              }}
            >
              <div className="form-row">
                <Field label="家庭">
                  <select
                    name="family_id"
                    required
                    value={selectedFamily}
                    onChange={(e) => setSelectedFamily(e.target.value)}
                  >
                    <option value="" disabled>
                      请选择家庭
                    </option>
                    {accounts.families.map((f) => (
                      <option key={f.id} value={f.id}>
                        {f.name}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="身份">
                  <select name="role">
                    <option value="child">小朋友</option>
                    <option value="parent">家长</option>
                  </select>
                </Field>
              </div>
              <Field label="称呼">
                <input name="name" placeholder="例如：小星" required maxLength={100} />
              </Field>
              <Field label="登录账号">
                <input
                  name="username"
                  placeholder="3–40 位字母、数字、_ 或 -"
                  pattern="[a-zA-Z0-9_-]{3,40}"
                  title="3–40 位字母、数字、下划线或短横线"
                  required
                  autoComplete="off"
                />
              </Field>
              <Field label="初始密码（至少 8 位）">
                <input
                  name="password"
                  type="password"
                  minLength={8}
                  maxLength={200}
                  required
                  autoComplete="new-password"
                />
              </Field>
              {!accounts.families.length && !loadingAccounts && (
                <p className="muted">先创建一个家庭，就可以分配账号。</p>
              )}
              <Button
                className="primary full"
                disabled={busy || !accounts.families.length}
                loading={pendingAction === 'account'}
              >
                分配账号 <ChevronRight size={17} />
              </Button>
            </form>
          </section>
        </div>
      </div>
      <section className="card backup-card admin-backup">
        <div className="admin-backup-summary">
          <div className="admin-stat-icon">
            <ShieldCheck size={25} />
          </div>
          <div>
            <h3>备份与恢复</h3>
            <p className="muted">保存家庭、账号、任务与星星流水，留住每一次成长。</p>
          </div>
          <Button
            className="secondary"
            disabled={busy}
            loading={pendingAction === 'backup'}
            onClick={() =>
              perform(
                'backup',
                async () => {
                  const b = await api('/api/admin/backup');
                  const url = URL.createObjectURL(
                    new Blob([JSON.stringify(b, null, 2)], { type: 'application/json' }),
                  );
                  const a = document.createElement('a');
                  a.href = url;
                  a.download = `star-backup-${currentDate()}.json`;
                  a.click();
                  setTimeout(() => URL.revokeObjectURL(url), 1000);
                },
                '备份已生成',
              )
            }
          >
            <Download size={18} /> 下载完整备份
          </Button>
        </div>
        <details className="admin-restore">
          <summary>
            恢复已有备份 <ChevronRight size={17} />
          </summary>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              const password = new FormData(e.currentTarget).get('password');
              if (
                await perform(
                  'restore',
                  () => api('/api/admin/restore', 'POST', { backup, password }),
                  '数据已恢复，请重新登录',
                )
              )
                logout();
            }}
          >
            <Field label="选择备份文件（JSON，最多 20MB）">
              <input
                type="file"
                accept=".json,application/json"
                required
                onChange={async (e) => {
                  const version = ++fileRead.current;
                  setBackup(null);
                  setFileName('');
                  setFileError('');
                  const f = e.target.files?.[0];
                  if (!f) return;
                  if (f.size > 20 * 1024 * 1024) {
                    setFileError('文件超过 20MB');
                    return;
                  }
                  try {
                    const value = JSON.parse(await f.text());
                    if (fileRead.current !== version) return;
                    setBackup(value);
                    setFileName(f.name);
                  } catch {
                    if (fileRead.current === version) setFileError('备份不是有效的 JSON 文件');
                  }
                }}
              />
            </Field>
            {fileError && (
              <div className="error" role="alert">
                {fileError}
              </div>
            )}
            {fileName && (
              <p className="admin-file-status" role="status">
                <Check size={16} /> 已选择：{fileName}
              </p>
            )}
            <div className="info-strip">
              恢复将替换当前全部数据。系统先自动保存恢复前备份，成功后所有账号需要重新登录。
            </div>
            <Field label="当前管理员密码">
              <input type="password" name="password" autoComplete="current-password" required />
            </Field>
            <label className="check-field">
              <input type="checkbox" required /> 我确认用此备份替换当前数据
            </label>
            <Button
              className="secondary full"
              disabled={busy || !backup}
              loading={pendingAction === 'restore'}
            >
              <Upload size={18} /> 确认恢复备份
            </Button>
          </form>
        </details>
      </section>
    </section>
  );
}
function ResetPassword({
  user,
  busy,
  act,
}: {
  user: User;
  busy: boolean;
  act: (fn: () => Promise<unknown>, message?: string) => Promise<boolean>;
}) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button className="text-btn" onClick={() => setOpen(true)}>
        重置密码
      </Button>
      <AnimatePresence>
        {open && (
          <Modal title={`重置 ${user.name} 的密码`} close={() => setOpen(false)}>
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                const password = new FormData(e.currentTarget).get('password');
                if (
                  await act(
                    () => api(`/api/admin/users/${user.id}`, 'PATCH', { password }),
                    '密码已重置，原登录已失效',
                  )
                )
                  setOpen(false);
              }}
            >
              <Field label="新密码（至少 8 位）">
                <input type="password" name="password" required minLength={8} maxLength={200} />
              </Field>
              <Button className="primary full" loading={busy}>
                确认重置
              </Button>
            </form>
          </Modal>
        )}
      </AnimatePresence>
    </>
  );
}
