import { createContext, useContext, useEffect, useId, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { LayoutGroup, motion, useReducedMotion } from 'framer-motion';
import type { HTMLMotionProps } from 'framer-motion';
import { LoaderCircle, Sparkles, X } from 'lucide-react';
import { exitFallbackMs, spring } from '../lib/springs';

// Adapted to native HTML and our themes from Fluid Functionalism's button,
// tabs and dialog patterns. See docs/fluid-functionalism.md and its MIT notice.
export function Button({
  children,
  loading = false,
  disabled,
  ...props
}: Omit<HTMLMotionProps<'button'>, 'children'> & { children?: ReactNode; loading?: boolean }) {
  const reduced = useReducedMotion();
  return (
    <motion.button
      {...props}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      data-loading={loading || undefined}
      whileTap={!disabled && !loading && !reduced ? { scale: 0.97 } : undefined}
      transition={spring.fast}
    >
      {loading ? (
        <>
          <span className="button-content">{children}</span>
          <LoaderCircle className="button-spinner" size={19} aria-hidden="true" />
        </>
      ) : (
        children
      )}
    </motion.button>
  );
}

const InteractionContext = createContext({ busy: false, error: '' });

export function InteractionSurface({
  busy,
  error,
  className,
  children,
}: {
  busy: boolean;
  error: string;
  className: string;
  children: ReactNode;
}) {
  return (
    <InteractionContext.Provider value={{ busy, error }}>
      <div className={className}>{children}</div>
    </InteractionContext.Provider>
  );
}

export function Modal({
  title,
  children,
  close,
}: {
  title: string;
  children: ReactNode;
  close: () => void;
}) {
  const { busy, error } = useContext(InteractionContext);
  const ref = useRef<HTMLDialogElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const startedOutside = useRef(false);
  const titleId = useId();
  const reduced = useReducedMotion();
  useEffect(() => {
    const dialog = ref.current;
    const trigger = document.activeElement;
    const triggerCard = trigger?.closest('.task-card, .reward-card, .rule-card, .review-card');
    dialog?.showModal();
    return () => {
      dialog?.close();
      const candidates = [
        trigger,
        triggerCard?.querySelector('button:not(:disabled)'),
        document.querySelector('.navigation [aria-current="page"]'),
      ];
      for (const candidate of candidates) {
        if (
          candidate instanceof HTMLElement &&
          candidate.isConnected &&
          !candidate.matches(':disabled') &&
          candidate.getClientRects().length
        ) {
          candidate.focus({ preventScroll: true });
          if (document.activeElement === candidate) break;
        }
      }
    };
  }, []);
  useEffect(() => {
    heading.current?.focus({ preventScroll: true });
  }, [title]);
  function outside(x: number, y: number) {
    const rect = ref.current?.getBoundingClientRect();
    return !!rect && (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom);
  }
  return (
    <motion.dialog
      ref={ref}
      className="modal"
      aria-labelledby={titleId}
      aria-busy={busy || undefined}
      initial={{ opacity: 0, scale: reduced ? 1 : 0.97, y: reduced ? 0 : 8 }}
      animate={{ opacity: 1, scale: 1, y: 0 }}
      exit={{
        opacity: 0,
        scale: reduced ? 1 : 0.985,
        y: reduced ? 0 : 4,
        transition: spring.slow.exit,
      }}
      transition={spring.slow}
      onCancel={(event) => {
        // Prevent native Escape from closing the top layer during a request or
        // before AnimatePresence has completed the exit.
        event.preventDefault();
        if (!busy) close();
      }}
      onPointerDown={(event) => {
        startedOutside.current =
          event.target === event.currentTarget && outside(event.clientX, event.clientY);
      }}
      onClick={(event) => {
        if (
          !busy &&
          startedOutside.current &&
          event.target === event.currentTarget &&
          outside(event.clientX, event.clientY)
        )
          close();
        startedOutside.current = false;
      }}
    >
      <div className="modal-head">
        <h2 id={titleId} ref={heading} tabIndex={-1}>
          {title}
        </h2>
        <Button
          type="button"
          className="icon-btn"
          aria-label="关闭"
          disabled={busy}
          onClick={close}
        >
          <X />
        </Button>
      </div>
      <div className="modal-body">
        {error && (
          <div className="error" role="alert">
            {error}
          </div>
        )}
        {children}
      </div>
    </motion.dialog>
  );
}

export function SubjectPicker({
  options,
  value,
  onChange,
}: {
  options: Record<string, string>;
  value: string;
  onChange: (value: string) => void;
}) {
  const id = useId();
  const reduced = useReducedMotion();
  const entries = Object.entries(options);
  return (
    <LayoutGroup id={id}>
      <div
        className="subject-tabs fluid-selection"
        role="group"
        aria-label="科目分类"
        onKeyDown={(event) => {
          if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
          const buttons = Array.from(event.currentTarget.querySelectorAll('button'));
          const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
          if (index < 0) return;
          event.preventDefault();
          const next =
            event.key === 'Home'
              ? 0
              : event.key === 'End'
                ? entries.length - 1
                : (index + (event.key === 'ArrowRight' ? 1 : -1) + entries.length) % entries.length;
          buttons[next].focus();
          onChange(entries[next][0]);
        }}
      >
        {entries.map(([key, label]) => (
          <Button
            key={key}
            type="button"
            className={key === value ? 'active' : ''}
            aria-pressed={key === value}
            onClick={() => onChange(key)}
          >
            {key === value &&
              (reduced ? (
                <span className="selection-indicator" aria-hidden="true" />
              ) : (
                <motion.span
                  className="selection-indicator"
                  aria-hidden="true"
                  layoutId="subject-selection"
                  transition={spring.moderate}
                />
              ))}
            <span className="selection-label">{label}</span>
          </Button>
        ))}
      </div>
    </LayoutGroup>
  );
}

export function Toast({ message, dismiss }: { message: string; dismiss: () => void }) {
  const [displayed, setDisplayed] = useState<{ text: string; id: number } | null>(null);
  const sequence = useRef(0);
  useEffect(() => {
    if (message) {
      setDisplayed({ text: message, id: ++sequence.current });
      return;
    }
    // Like the upstream dialog, release even if a suspended animation frame
    // never delivers onAnimationComplete. The owner controls unmount directly.
    const timer = setTimeout(() => setDisplayed(null), exitFallbackMs(spring.moderate));
    return () => clearTimeout(timer);
  }, [message]);
  return (
    displayed && (
      <ToastMessage
        key={displayed.id}
        message={displayed.text}
        open={!!message}
        dismiss={dismiss}
        release={() => {
          if (!message) setDisplayed(null);
        }}
      />
    )
  );
}

function ToastMessage({
  message,
  open,
  dismiss,
  release,
}: {
  message: string;
  open: boolean;
  dismiss: () => void;
  release: () => void;
}) {
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  const reduced = useReducedMotion();
  const dismissRef = useRef(dismiss);
  dismissRef.current = dismiss;
  useEffect(() => {
    if (!open || hovered || focused) return;
    const timer = setTimeout(() => dismissRef.current(), 6500);
    return () => clearTimeout(timer);
  }, [message, open, hovered, focused]);
  return (
    <motion.div
      key={message}
      className="toast"
      data-state={open ? 'open' : 'closed'}
      role="status"
      aria-atomic="true"
      initial={{ opacity: 0, x: '-50%', y: reduced ? 0 : 10 }}
      animate={{ opacity: open ? 1 : 0, x: '-50%', y: 0 }}
      transition={open ? spring.moderate : spring.moderate.exit}
      onAnimationComplete={release}
      onPointerEnter={() => setHovered(true)}
      onPointerLeave={() => setHovered(false)}
      onFocusCapture={() => setFocused(true)}
      onBlurCapture={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setFocused(false);
      }}
    >
      <Sparkles size={20} aria-hidden="true" />
      <span>{message}</span>
      <Button type="button" className="toast-close" aria-label="关闭提示" onClick={dismiss}>
        <X size={18} />
      </Button>
    </motion.div>
  );
}
