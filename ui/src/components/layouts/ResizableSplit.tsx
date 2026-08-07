import React, {
  ReactNode,
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react';

interface ResizableSplitProps {
  left: ReactNode;
  right: ReactNode;
  /** Which pane keeps a fixed pixel width; the other pane flexes. */
  fixed: 'left' | 'right';
  defaultSize: number;
  minSize: number;
  maxSize: number;
  /** localStorage key persisting the chosen size across sessions. */
  storageKey: string;
  style?: React.CSSProperties;
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

/**
 * Two panes separated by a draggable vertical divider (no external
 * dependency). The fixed pane's width is dragged in pixels, clamped to
 * [minSize, maxSize] and persisted to localStorage. Used for the schema
 * browser ↔ content split and the details content ↔ sidebar split.
 */
export default function ResizableSplit({
  left,
  right,
  fixed,
  defaultSize,
  minSize,
  maxSize,
  storageKey,
  style,
}: ResizableSplitProps) {
  const [size, setSize] = useState<number>(() => {
    try {
      const stored = Number(window.localStorage.getItem(storageKey));
      if (Number.isFinite(stored) && stored > 0) {
        return clamp(stored, minSize, maxSize);
      }
    } catch {
      // Storage unavailable (private mode etc.) — fall back to the default.
    }
    return defaultSize;
  });
  const [dragging, setDragging] = useState(false);
  const dragState = useRef<{ startX: number; startSize: number } | null>(null);
  const sizeRef = useRef(size);
  sizeRef.current = size;

  const onPointerDown = useCallback((e: React.PointerEvent) => {
    e.preventDefault();
    // Capture the pointer so move/up keep arriving on this element even when the
    // cursor leaves the window. Without capture a release outside the viewport
    // is missed and the drag "sticks" — re-entering without a held button keeps
    // resizing until the next click.
    e.currentTarget.setPointerCapture(e.pointerId);
    dragState.current = { startX: e.clientX, startSize: sizeRef.current };
    setDragging(true);
  }, []);

  const onPointerMove = useCallback(
    (e: React.PointerEvent) => {
      if (!dragState.current) return;
      const delta = e.clientX - dragState.current.startX;
      // Dragging right grows a left-fixed pane but shrinks a right-fixed one.
      const next =
        fixed === 'left'
          ? dragState.current.startSize + delta
          : dragState.current.startSize - delta;
      setSize(clamp(next, minSize, maxSize));
    },
    [fixed, minSize, maxSize],
  );

  const endDrag = useCallback(
    (e: React.PointerEvent) => {
      if (!dragState.current) return;
      dragState.current = null;
      setDragging(false);
      if (e.currentTarget.hasPointerCapture(e.pointerId)) {
        e.currentTarget.releasePointerCapture(e.pointerId);
      }
      try {
        window.localStorage.setItem(storageKey, String(sizeRef.current));
      } catch {
        // Best effort only.
      }
    },
    [storageKey],
  );

  // Suppress text selection / iframe capture while dragging.
  useEffect(() => {
    if (!dragging) return;
    const prevUserSelect = document.body.style.userSelect;
    document.body.style.userSelect = 'none';
    return () => {
      document.body.style.userSelect = prevUserSelect;
    };
  }, [dragging]);

  const fixedStyle: React.CSSProperties = {
    width: size,
    minWidth: size,
    maxWidth: size,
    display: 'flex',
    flexDirection: 'column',
    minHeight: 0,
    overflow: 'hidden',
  };
  const flexStyle: React.CSSProperties = {
    flex: 1,
    minWidth: 0,
    display: 'flex',
    flexDirection: 'column',
    minHeight: 0,
  };

  return (
    <div style={{ display: 'flex', flex: 1, minHeight: 0, ...style }}>
      <div style={fixed === 'left' ? fixedStyle : flexStyle}>{left}</div>
      <div
        role="separator"
        aria-orientation="vertical"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        style={{
          width: 5,
          cursor: 'col-resize',
          flexShrink: 0,
          // Let the pointer drag own the gesture on touch (no scroll/pan).
          touchAction: 'none',
          // The visible 1px rule sits inside a wider hit area.
          background: dragging
            ? 'linear-gradient(to right, transparent 1px, #1677ff 1px, #1677ff 3px, transparent 3px)'
            : 'linear-gradient(to right, transparent 2px, lightgrey 2px, lightgrey 3px, transparent 3px)',
        }}
      />
      <div style={fixed === 'right' ? fixedStyle : flexStyle}>{right}</div>
    </div>
  );
}
