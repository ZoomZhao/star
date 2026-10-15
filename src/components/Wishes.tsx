import {
  Heart,
  Plus,
  Clock3,
  ChevronRight,
  Pencil,
  Power,
  Check,
  X,
  Star,
  Gift,
  IceCreamBowl,
  Gamepad2,
  Clapperboard,
  TentTree,
} from 'lucide-react';
import { Button } from './Fluid';
import type { Dashboard, Reward, Redemption } from '../types';

export const wishIcons = {
  icecream: { label: '冰淇淋', icon: IceCreamBowl },
  toy: { label: '玩具', icon: Gamepad2 },
  movie: { label: '电影', icon: Clapperboard },
  picnic: { label: '野餐', icon: TentTree },
  gift: { label: '礼物', icon: Gift },
};

export function WishIcon({ icon }: { icon: string }) {
  const key = icon in wishIcons ? (icon as keyof typeof wishIcons) : 'gift';
  const Icon = wishIcons[key].icon;
  return (
    <span className={`wish-icon wish-icon-${key}`}>
      <Icon strokeWidth={1.8} aria-hidden="true" />
    </span>
  );
}

export function WishShop({
  data,
  isParent,
  busy,
  online,
  filter,
  onFilter,
  onCreate,
  onEdit,
  onRedeem,
  onToggle,
  onReview,
  onHistory,
}: {
  data: Dashboard;
  isParent: boolean;
  busy: boolean;
  online: boolean;
  filter: string;
  onFilter: (value: string) => void;
  onCreate: () => void;
  onEdit: (reward: Reward) => void;
  onRedeem: (reward: Reward) => void;
  onToggle: (reward: Reward) => void;
  onReview: () => void;
  onHistory: () => void;
}) {
  const pending = data.redemptions.filter((r) => r.status === 'pending').length;
  const active = data.rewards.filter((r) => r.active).length;
  const rewards = data.rewards.filter((r) =>
    isParent ? filter === 'all' || (filter === 'active' ? !!r.active : !r.active) : !!r.active,
  );
  return (
    <section className="shop-page">
      <div className="wish-heading">
        <h2>
          <Heart aria-hidden="true" />
          心愿小铺
        </h2>
        <div className="wish-heading-actions">
          <Button
            className="wish-history-button"
            aria-label="兑换记录"
            title="兑换记录"
            onClick={onHistory}
          >
            <Clock3 size={20} />
          </Button>
          {isParent && (
            <Button className="primary" aria-label="添加奖励" onClick={onCreate}>
              <Plus size={18} />
              添加
            </Button>
          )}
        </div>
      </div>
      <div className="wish-overview">
        <div className="wish-balance">
          <span className="wish-balance-icon">
            <Star />
          </span>
          <div>
            <span>可用星星</span>
            <strong>
              {data.wallet.balance}
              <small>星</small>
            </strong>
          </div>
        </div>
        <Button
          className="wish-pending-link"
          aria-label={isParent ? '审核兑换申请' : '查看兑换记录'}
          onClick={isParent ? onReview : onHistory}
        >
          <span>
            {pending ? (
              <>
                <b>{pending}</b> 个心愿待确认
              </>
            ) : (
              '期待下一份小心愿'
            )}
            <small>{isParent ? '确认兑换后扣星' : '选一个喜欢的小心愿'}</small>
          </span>
          <ChevronRight size={17} />
        </Button>
      </div>
      {isParent ? (
        <div className="wish-filters" role="group" aria-label="奖励状态">
          {(
            [
              ['all', '全部', data.rewards.length],
              ['active', '已上架', active],
              ['inactive', '已下架', data.rewards.length - active],
            ] as const
          ).map(([key, label, count]) => (
            <Button key={key} aria-pressed={filter === key} onClick={() => onFilter(key)}>
              {label}
              <span>{count}</span>
            </Button>
          ))}
        </div>
      ) : (
        <p className="wish-section-note">用攒下的星星，兑换喜欢的时光</p>
      )}
      <div className="rewards-grid">
        {rewards.map((r) => {
          const waiting = data.redemptions.some(
            (x) => x.reward_id === r.id && x.status === 'pending',
          );
          const missing = Math.max(0, r.cost - data.wallet.balance);
          return (
            <article className={`reward-card ${r.active ? '' : 'reward-inactive'}`} key={r.id}>
              <div className="wish-card-heading">
                <WishIcon icon={r.icon} />
                <div className="wish-card-copy">
                  <h3>{r.title}</h3>
                  {r.description && <p>{r.description}</p>}
                </div>
              </div>
              <div className="wish-card-status">
                <span
                  className={`wish-badge ${isParent ? (r.active ? 'available' : 'inactive') : waiting ? 'pending' : missing ? 'saving' : 'available'}`}
                >
                  {isParent ? (
                    r.active ? (
                      <>
                        <Check />
                        已上架
                      </>
                    ) : (
                      <>
                        <Power />
                        已下架
                      </>
                    )
                  ) : waiting ? (
                    <>
                      <Clock3 />
                      等待确认
                    </>
                  ) : missing ? (
                    <>
                      <Star />
                      攒星中
                    </>
                  ) : (
                    <>
                      <Check />
                      可以兑换
                    </>
                  )}
                </span>
                {isParent && waiting && (
                  <span className="wish-waiting">
                    <Clock3 size={12} />
                    有待确认申请
                  </span>
                )}
                {!isParent && !waiting && (
                  <span className="wish-progress-label">
                    {missing
                      ? `已攒 ${Math.max(0, data.wallet.balance)} / ${r.cost} 星`
                      : '已经攒够啦'}
                  </span>
                )}
              </div>
              <div className="reward-bottom">
                <strong className="wish-price">
                  <Star size={19} />
                  <span>{r.cost}</span>
                  <small>星</small>
                </strong>
                <div className="wish-card-actions">
                  {isParent ? (
                    <>
                      <Button
                        className="wish-edit"
                        aria-label="编辑奖励"
                        disabled={busy || !online}
                        onClick={() => onEdit(r)}
                      >
                        <Pencil size={16} />
                        编辑
                      </Button>
                      <Button
                        className="wish-toggle"
                        aria-label={r.active ? '下架奖励' : '重新上架'}
                        disabled={busy || !online}
                        onClick={() => onToggle(r)}
                      >
                        <Power size={16} />
                        {r.active ? '下架' : '上架'}
                      </Button>
                    </>
                  ) : (
                    <Button
                      className="primary"
                      disabled={busy || !online || waiting || missing > 0}
                      onClick={() => onRedeem(r)}
                    >
                      {waiting ? <Clock3 size={16} /> : <Gift size={16} />}
                      {waiting ? '等待确认' : missing ? `还差 ${missing} 星` : '我想兑换'}
                    </Button>
                  )}
                </div>
              </div>
              {!isParent && (
                <div
                  className={`wish-progress ${waiting ? 'pending' : ''}`}
                  role="progressbar"
                  aria-label={`${r.title}的星星进度`}
                  aria-valuemin={0}
                  aria-valuemax={r.cost}
                  aria-valuenow={Math.min(r.cost, Math.max(0, data.wallet.balance))}
                >
                  <span
                    style={{
                      width: `${Math.min(100, (Math.max(0, data.wallet.balance) / r.cost) * 100)}%`,
                    }}
                  />
                </div>
              )}
            </article>
          );
        })}
      </div>
      {!rewards.length && (
        <div className="wish-empty">
          <Gift />
          <h3>{isParent ? '这里还没有心愿' : '小心愿，准备中'}</h3>
          <p>
            {isParent ? '添加一个心愿，或切换上架状态查看。' : '和家长一起，约定第一份奖励吧。'}
          </p>
          {isParent && (
            <Button className="primary" onClick={onCreate}>
              <Plus size={18} />
              添加奖励
            </Button>
          )}
        </div>
      )}
      <p className="wish-footnote">
        {isParent
          ? '心愿由家人共享，兑换记录按孩子分别保存。'
          : '申请时不会扣星，家长确认后才会扣除。'}
      </p>
    </section>
  );
}

export function WishHistory({ redemptions }: { redemptions: Redemption[] }) {
  return (
    <div className="wish-history">
      {redemptions.length ? (
        redemptions.map((r) => {
          const StatusIcon = r.status === 'pending' ? Clock3 : r.status === 'approved' ? Check : X;
          return (
            <article className="wish-history-row" key={r.id}>
              <WishIcon icon={r.icon} />
              <div>
                <h3>{r.title}</h3>
                <p>
                  {r.cost} 星 ·{' '}
                  {new Intl.DateTimeFormat('zh-CN', {
                    timeZone: 'Asia/Shanghai',
                    month: 'numeric',
                    day: 'numeric',
                    hour: '2-digit',
                    minute: '2-digit',
                    hour12: false,
                  }).format(new Date(r.created_at))}
                </p>
                <span className={`wish-badge ${r.status}`}>
                  <StatusIcon />
                  {r.status === 'pending'
                    ? '等待确认'
                    : r.status === 'approved'
                      ? '兑换成功'
                      : '已退回'}
                </span>
                {r.review_note && <p className="wish-review-note">{r.review_note}</p>}
              </div>
            </article>
          );
        })
      ) : (
        <div className="wish-empty">
          <Gift />
          <h3>还没有兑换记录</h3>
          <p>攒下小星星，期待第一个心愿实现。</p>
        </div>
      )}
    </div>
  );
}
