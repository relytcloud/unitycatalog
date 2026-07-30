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

  const onMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    dragState.current = { startX: e.clientX, startSize: sizeRef.current };
    setDragging(true);
  }, []);

  useEffect(() => {
    if (!dragging) return;
    const onMouseMove = (e: MouseEvent) => {
      if (!dragState.current) return;
      const delta = e.clientX - dragState.current.startX;
      // Dragging right grows a left-fixed pane but shrinks a right-fixed one.
      const next =
        fixed === 'left'
          ? dragState.current.startSize + delta
          : dragState.current.startSize - delta;
      setSize(clamp(next, minSize, maxSize));
    };
    const onMouseUp = () => {
      dragState.current = null;
      setDragging(false);
      try {
        window.localStorage.setItem(storageKey, String(sizeRef.current));
      } catch {
        // Best effort only.
      }
    };
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
    // Suppress text selection / iframe capture while dragging.
    const prevUserSelect = document.body.style.userSelect;
    document.body.style.userSelect = 'none';
    return () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
      document.body.style.userSelect = prevUserSelect;
    };
  }, [dragging, fixed, minSize, maxSize, storageKey]);

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
        onMouseDown={onMouseDown}
        style={{
          width: 5,
          cursor: 'col-resize',
          flexShrink: 0,
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
