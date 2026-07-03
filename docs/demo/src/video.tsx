import type { CSSProperties, ReactNode } from "react";

import { AbsoluteFill, Img, interpolate, Sequence, staticFile, useCurrentFrame } from "remotion";

type SceneProps = {
  start: number;
  duration: number;
  children: (progress: number) => ReactNode;
};

type CaptureProps = {
  src: string;
  fit?: CSSProperties["objectFit"];
  scale?: number;
  x?: number;
  y?: number;
  radius?: number;
  shadow?: boolean;
  style?: CSSProperties;
};

const blue = "#0f6b9a";
const deep = "#08283a";
const ink = "#17212b";
const green = "#16a34a";
const lanes = ["Inbox", "Ready for Codex", "In Progress", "Human Review", "Merging", "Done"];

const ease = (value: number) => 1 - Math.pow(1 - value, 3);
const clamp = (value: number) => Math.max(0, Math.min(1, value));

export function ReadmeDemo() {
  return (
    <AbsoluteFill style={styles.stage}>
      <Scene start={0} duration={110}>{(progress) => <Intro progress={progress} />}</Scene>
      <Scene start={110} duration={120}>{(progress) => <BoardScene image="trello-board-inbox.jpg" progress={progress} caption="Plan work where your team already plans work." activeLane="Inbox" status="New work request" detail="A Trello card starts the whole workflow." />}</Scene>
      <Scene start={230} duration={180}>{(progress) => <BoardScene image="trello-board-ready.jpg" progress={progress} caption="Move the card to Ready for Codex." activeLane="Ready for Codex" fromLane="Inbox" showCursor status="Human handoff" detail="One move tells Symphony this task is ready." />}</Scene>
      <Scene start={410} duration={180}>{(progress) => <BoardScene image="trello-board-in-progress.jpg" progress={progress} caption="Symphony picks it up automatically." activeLane="In Progress" fromLane="Ready for Codex" status="Automated pickup" detail="Symphony starts Codex and moves the same card forward." />}</Scene>
      <Scene start={590} duration={300}>{(progress) => <WorkpadScene progress={progress} phase="initial" />}</Scene>
      <Scene start={890} duration={130}>{(progress) => <BoardScene image="trello-board-human-review-staged.jpg" progress={progress} caption="The PR is ready for review." activeLane="Human Review" fromLane="In Progress" status="Review handoff" detail="The Trello card now points reviewers to the pull request." />}</Scene>
      <Scene start={1020} duration={220}>{(progress) => <GithubReview progress={progress} />}</Scene>
      <Scene start={1240} duration={150}>{(progress) => <BoardScene image="trello-board-ready.jpg" progress={progress} caption="Move the card back and Codex continues." activeLane="Ready for Codex" fromLane="Human Review" showCursor status="Review feedback queued" detail="A review comment becomes the next Codex task." />}</Scene>
      <Scene start={1390} duration={150}>{(progress) => <BoardScene image="trello-board-in-progress.jpg" progress={progress} caption="Codex reopens the same task context." activeLane="In Progress" fromLane="Ready for Codex" status="Rework running" detail="The worker reads the review thread and updates the PR." />}</Scene>
      <Scene start={1540} duration={240}>{(progress) => <WorkpadScene progress={progress} phase="rework" />}</Scene>
      <Scene start={1780} duration={130}>{(progress) => <BoardScene image="trello-board-human-review-staged.jpg" progress={progress} caption="Symphony brings the updated PR back for review." activeLane="Human Review" fromLane="In Progress" status="Updated PR" detail="The same card carries the rework and validation context." />}</Scene>
      <Scene start={1910} duration={170}>{(progress) => <GithubResolved progress={progress} />}</Scene>
      <Scene start={2080} duration={140}>{(progress) => <MergeScene progress={progress} />}</Scene>
      <Scene start={2220} duration={120}>{(progress) => <BoardScene image="trello-board-done.jpg" progress={progress} caption="The card lands in Done." activeLane="Done" fromLane="Merging" status="Merged and complete" detail="The PR is merged and the board shows the task as finished." />}</Scene>
      <Scene start={2340} duration={130}>{(progress) => <AnywhereScene progress={progress} />}</Scene>
      <Scene start={2470} duration={150}>{(progress) => <FinalHero progress={progress} />}</Scene>
    </AbsoluteFill>
  );
}

export function Poster() {
  return (
    <AbsoluteFill style={styles.stage}>
      <FinalHero progress={1} />
    </AbsoluteFill>
  );
}

function Scene({ start, duration, children }: SceneProps) {
  const frame = useCurrentFrame();
  const progress = clamp((frame - start) / duration);

  return (
    <Sequence from={start} durationInFrames={duration}>
      <AbsoluteFill>{children(progress)}</AbsoluteFill>
    </Sequence>
  );
}

function Intro({ progress }: { progress: number }) {
  const lift = interpolate(ease(progress), [0, 1], [30, 0]);

  return (
    <AbsoluteFill style={styles.intro}>
      <div style={{ ...styles.introContent, transform: `translateY(${lift}px)` }}>
        <div style={styles.brandPill}>Symphony for Trello</div>
        <h1 style={styles.title}>
          From Trello card<br />to merged pull request
        </h1>
        <p style={styles.subtitle}>A real run: Trello plans the work, Codex implements it, and Symphony keeps the handoff moving.</p>
      </div>
    </AbsoluteFill>
  );
}

function AnywhereScene({ progress }: { progress: number }) {
  const phoneLift = interpolate(ease(progress), [0, 1], [80, 0]);
  const boardScale = interpolate(ease(progress), [0, 1], [0.98, 1]);

  return (
    <SceneShell
      caption="The same workflow works from phone or laptop."
      subcaption="The board stays familiar: plan, review, and track work where your team already looks."
    >
      <div style={styles.anywhereLayout}>
        <MacWindow style={{ ...styles.anywhereBoardFrame, transform: `scale(${boardScale})` }}>
          <Capture src="trello-board-done.jpg" fit="cover" shadow={false} style={{ opacity: 1 }} />
        </MacWindow>
        <div style={styles.anywherePhoneColumn}>
          <div style={{ ...styles.phoneFrame, transform: `translateY(${phoneLift}px)` }}>
            <Capture src="trello-mobile-card.jpg" fit="cover" radius={38} shadow={false} />
          </div>
          <div style={styles.anywhereNote}>Plan, review, and track the same work from anywhere.</div>
        </div>
      </div>
    </SceneShell>
  );
}


function MacWindow({ children, style }: { children: React.ReactNode; style?: React.CSSProperties }) {
  return (
    <div style={{ ...styles.macWindow, ...style }}>
      <div style={styles.macChrome}>
        <div style={{ ...styles.macDot, background: "#ff5f56" }} />
        <div style={{ ...styles.macDot, background: "#ffbd2e" }} />
        <div style={{ ...styles.macDot, background: "#27c93f" }} />
      </div>
      <div style={styles.macContent}>{children}</div>
    </div>
  );
}

function BoardScene({
  image,
  progress,
  caption,
  activeLane,
  fromLane,
  showCursor = false,
  status,
  detail,
}: {
  image: string;
  progress: number;
  caption: string;
  activeLane: string;
  fromLane?: string;
  showCursor?: boolean;
  status: string;
  detail: string;
}) {
  const moving = fromLane !== undefined && fromLane !== activeLane;
  const sourceLane = fromLane ?? activeLane;

  return (
    <SceneShell caption={caption} eyebrow={status} subcaption={detail}>
      <div style={styles.realBoardScene}>
        <MacWindow style={styles.realBoardFrame}>
          <Capture src={image} fit="cover" shadow={false} style={{ opacity: 1 }} />
          {moving && showCursor ? <CursorDrag fromLane={sourceLane} toLane={activeLane} progress={progress} /> : null}
          {moving && !showCursor ? <AutomationMove fromLane={sourceLane} toLane={activeLane} progress={progress} /> : null}
        </MacWindow>
      </div>
    </SceneShell>
  );
}



function WorkpadScene({ progress, phase }: { progress: number; phase: "initial" | "rework" }) {
  const isRework = phase === "rework";
  const bullets = isRework
    ? [
        "PR feedback: report whichever Trello credential is absent.",
        "Updated src/config.ts for missing API key, token, or both.",
        "Added tests for every missing-credential case.",
        "Validation passed: npm run typecheck; npm test.",
      ]
    : [
        "Repository selected from the Trello card URL.",
        "Created task branch clarify-missing-trello-token-error.",
        "Updated src/config.ts with a clearer status-check error.",
        "Validation passed: npm run typecheck; npm test.",
      ];

  return (
    <SceneShell
      caption={isRework ? "Codex reads the review and updates the implementation." : "Progress stays visible on the Trello card."}
      subcaption={isRework ? "The rework starts from the review comment and keeps the same PR alive." : "The workpad shows you what Codex is doing before the PR is ready."}
    >
      <div style={styles.workpadLayout}>
        <WorkpadCard progress={progress} title={isRework ? "Codex Workpad - rework" : "Codex Workpad"} bullets={bullets} />
        <div style={styles.workpadContextPanel}>
          <span style={styles.contextPill}>Live Trello status</span>
          <h3 style={styles.contextTitle}>{isRework ? "Review feedback is being handled" : "Implementation is in progress"}</h3>
          <p style={styles.contextText}>
            {isRework
              ? "Codex keeps the same card and PR, then records what changed."
              : "You can see the plan, progress, and validation without opening host logs."}
          </p>
        </div>
      </div>
    </SceneShell>
  );
}

function GithubReview({ progress }: { progress: number }) {
  return (
    <SceneShell caption="Review the PR and leave one clear comment." subcaption="You review the actual code and leave one small, actionable comment.">
      <MacWindow style={styles.githubFrame}>
        <Capture
          src="github-review-diff.jpg"
          fit="cover"
          scale={interpolate(ease(progress), [0, 1], [1.02, 1.06])}
          shadow={false}
          style={{ objectPosition: "50% 42%" }}
        />
        <Label top={24} right={24}>Real GitHub PR diff and review thread</Label>
      </MacWindow>
    </SceneShell>
  );
}

function GithubResolved({ progress }: { progress: number }) {
  return (
    <SceneShell caption="Comment answered. Checks green." subcaption="Codex responds with the concrete change and validation from the rework pass.">
      <MacWindow style={styles.githubFrame}>
        <Capture
          src="github-comment-resolved.jpg"
          fit="cover"
          scale={interpolate(ease(progress), [0, 1], [1.02, 1.06])}
          shadow={false}
          style={{ objectPosition: "50% 42%" }}
        />
        <Label top={24} right={24}>Same PR after rework</Label>
      </MacWindow>
    </SceneShell>
  );
}

function MergeScene({ progress }: { progress: number }) {
  const boardX = interpolate(ease(progress), [0, 1], [-40, 0]);
  const githubX = interpolate(ease(progress), [0, 1], [40, 0]);

  return (
    <SceneShell caption="Move it to Merging. Symphony merges the PR." subcaption="Trello remains the control surface while GitHub records the merged code.">
      <div style={styles.mergeGrid}>
        <div style={{ ...styles.panel, transform: `translateX(${boardX}px)` }}>
          <Capture src="trello-board-merging.jpg" fit="contain" shadow={false} />
          <Label bottom={34} left={34}>Trello: Merging</Label>
        </div>
        <div style={{ ...styles.panel, transform: `translateX(${githubX}px)` }}>
          <Capture src="github-pr-merged.jpg" fit="contain" shadow={false} />
          <Label bottom={34} left={34}>GitHub: merged</Label>
        </div>
      </div>
    </SceneShell>
  );
}

function FinalHero({ progress }: { progress: number }) {
  const lift = interpolate(ease(progress), [0, 1], [42, 0]);

  return (
    <AbsoluteFill style={styles.finalHero}>
      <div style={{ ...styles.finalCopy, transform: `translateY(${lift}px)` }}>
        <h2 style={styles.finalHeadline}>You plan work in Trello. Codex implements it. Symphony keeps everything moving.</h2>
        <p style={styles.finalTagline}>From phone to laptop, from card to merged PR. No IDE required. No CLI babysitting.</p>
      </div>
      <div style={styles.finalMedia}>
        <div style={styles.finalTile}>
          <MacWindow style={styles.finalPanel}>
            <Capture src="trello-board-done.jpg" fit="cover" shadow={false} />
          </MacWindow>
          <div style={styles.finalPanelCaption}>Trello board</div>
        </div>
        <div style={styles.finalTile}>
          <MacWindow style={styles.finalPanel}>
            <Capture src="github-pr-merged.jpg" fit="cover" shadow={false} />
          </MacWindow>
          <div style={styles.finalPanelCaption}>Pull request: Merged</div>
        </div>
      </div>
    </AbsoluteFill>
  );
}


function WorkpadCard({ progress, title, bullets }: { progress: number; title: string; bullets: string[] }) {
  return (
    <div style={styles.workpadCard}>
      <div style={styles.workpadHeader}>## {title}</div>
      <div style={styles.workpadSection}>Plan / Progress / Validation</div>
      <ul style={styles.workpadList}>
        {bullets.map((bullet, index) => {
          const visible = clamp((progress - index * 0.13) / 0.28);

          return (
            <li
              key={bullet}
              style={{
                opacity: visible,
                transform: `translateY(${interpolate(ease(visible), [0, 1], [18, 0])}px)`,
              }}
            >
              {bullet}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function Capture({ src, fit = "cover", scale = 1, x = 0, y = 0, radius = 22, shadow = true, style }: CaptureProps) {
  return (
    <Img
      src={staticFile(`captures/${src}`)}
      style={{
        width: "100%",
        height: "100%",
        objectFit: fit,
        borderRadius: radius,
        boxShadow: shadow ? "0 28px 80px rgba(8, 40, 58, 0.28)" : undefined,
        transform: `translate(${x}px, ${y}px) scale(${scale})`,
        transformOrigin: "center center",
        ...style,
      }}
    />
  );
}

function SceneShell({
  caption,
  eyebrow,
  subcaption,
  children,
}: {
  caption: string;
  eyebrow?: string;
  subcaption?: string;
  children: ReactNode;
}) {
  return (
    <AbsoluteFill style={styles.scene}>
      <Caption eyebrow={eyebrow} subcaption={subcaption}>
        {caption}
      </Caption>
      <div style={styles.sceneContent}>{children}</div>
    </AbsoluteFill>
  );
}

function Caption({ children, eyebrow, subcaption }: { children: ReactNode; eyebrow?: string; subcaption?: string }) {
  return (
    <div style={styles.caption}>
      {eyebrow ? <span style={styles.captionEyebrow}>{eyebrow}</span> : null}
      <strong style={{ gridColumn: eyebrow ? "2" : "1 / -1" }}>{children}</strong>
      {subcaption ? (
        <span style={{ ...styles.captionSubtext, gridColumn: eyebrow ? "2" : "1 / -1" }}>{subcaption}</span>
      ) : null}
    </div>
  );
}

function Label({ children, top, left, bottom, right }: { children: ReactNode; top?: number; left?: number; bottom?: number; right?: number }) {
  return (
    <div style={{ ...styles.label, top, left, bottom, right }}>
      {children}
    </div>
  );
}

function Callout({ children, top, left, bottom, right, width }: { children: ReactNode; top?: number; left?: number; bottom?: number; right?: number; width: number }) {
  return <div style={{ ...styles.callout, top, left, bottom, right, width }}>{children}</div>;
}

function AutomationMove({ fromLane, toLane, progress }: { fromLane: string; toLane: string; progress: number }) {
  const start = laneCenter(fromLane);
  const end = laneCenter(toLane);
  const forward = end >= start;
  const left = Math.min(start, end);
  const width = Math.abs(end - start);
  const arrowStyle: CSSProperties = {
    ...styles.automationArrow,
    transform: `translateY(-50%) rotate(${forward ? 0 : 180}deg)`,
  };

  if (forward) {
    arrowStyle.right = -10;
  } else {
    arrowStyle.left = -10;
  }

  return (
    <div style={{ ...styles.automationMove, left: `${left}%`, width: `${width}%`, opacity: moveVisibility(progress) }}>
      <div style={styles.automationLine} />
      <div style={arrowStyle} />
      <div style={styles.automationBadge}>Symphony moves it</div>
    </div>
  );
}

function CursorDrag({ fromLane, toLane, progress }: { fromLane: string; toLane: string; progress: number }) {
  const left = interpolate(dragProgress(progress), [0, 1], [laneCenter(fromLane), laneCenter(toLane)]);
  const top = interpolate(dragProgress(progress), [0, 1], [314, 322]);

  return (
    <div style={{ ...styles.cursorDrag, left: `${left}%`, top, opacity: moveVisibility(progress) }}>
      <svg width="48" height="56" viewBox="0 0 70 82" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path
          d="M12 8L56 53L36 57L27 76L12 8Z"
          fill="white"
          stroke={deep}
          strokeWidth="5"
          strokeLinejoin="round"
        />
        <path d="M36 58L51 76" stroke={deep} strokeWidth="6" strokeLinecap="round" />
      </svg>
    </div>
  );
}

function storyTaskCard(lane: string, activeLane: string, progress: number, fromLane?: string): CSSProperties {
  const source = lane === fromLane && lane !== activeLane;
  return {
    ...styles.storyTaskCard,
    opacity: source ? 0.42 * (1 - dragProgress(progress)) : 1,
    transform: `translateY(${source ? 0 : interpolate(ease(progress), [0, 1], [12, 0])}px)`,
  };
}

function dragProgress(progress: number) {
  return ease(clamp((progress - 0.08) / 0.5));
}

function moveVisibility(progress: number) {
  return clamp((progress - 0.04) / 0.08) * clamp((0.78 - progress) / 0.12);
}

function laneCenter(lane: string) {
  return laneLeft(lane, laneWidth() / 2);
}

function laneCardLeft(lane: string) {
  return laneLeft(lane, 0);
}

function laneLeft(lane: string, inset: number) {
  return laneIndex(lane) * laneWidth() + inset;
}

function laneIndex(lane: string) {
  return Math.max(0, lanes.indexOf(lane));
}

function laneWidth() {
  return 100 / lanes.length;
}

const styles: Record<string, CSSProperties> = {
  stage: {
    background: "#080a0f", // cinematic dark
    color: "white",
    fontFamily: 'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    overflow: "hidden",
  },
  scene: {
    display: "grid",
    gridTemplateRows: "auto 1fr",
    gap: 30,
    padding: "40px 60px 50px",
    background: "#080a0f",
  },
  sceneContent: {
    position: "relative",
    minHeight: 0,
  },
  introContent: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    textAlign: 'center',
  },
  intro: {
    justifyContent: "center",
    padding: "0 150px",
    background: "#080a0f",
    color: "white",
  },
  brandPill: {
    width: "fit-content",
    border: "1px solid rgba(255, 255, 255, 0.15)",
    borderRadius: 999,
    padding: "12px 24px",
    fontSize: 22,
    letterSpacing: "0.04em",
    background: "rgba(255,255,255,0.05)",
    color: "#fff",
    fontWeight: 700,
    textTransform: "uppercase",
  },
  title: {
    margin: "38px 0 0",
    fontSize: 104,
    lineHeight: 1.05,
    letterSpacing: "-0.03em",
    fontWeight: 900,
    maxWidth: 1200,
    background: "-webkit-linear-gradient(#fff, #9ca3af)",
    WebkitBackgroundClip: "text",
    WebkitTextFillColor: "transparent",
  },
  subtitle: {
    marginTop: 30,
    maxWidth: 980,
    fontSize: 34,
    lineHeight: 1.35,
    color: "#9ca3af",
    fontWeight: 500,
  },
  macWindow: {
    display: "flex",
    flexDirection: "column",
    borderRadius: 16,
    overflow: "hidden",
    background: "#1e1e1e",
    border: "1px solid rgba(255,255,255,0.1)",
    boxShadow: "0 25px 80px rgba(0, 0, 0, 0.6), 0 0 40px rgba(255,255,255,0.05)",
  },
  macChrome: {
    height: 38,
    background: "#2d2d2d",
    display: "flex",
    alignItems: "center",
    padding: "0 16px",
    gap: 8,
    borderBottom: "1px solid rgba(0,0,0,0.5)",
  },
  macDot: {
    width: 12,
    height: 12,
    borderRadius: "50%",
  },
  macContent: {
    flex: 1,
    position: "relative",
    minHeight: 0,
    background: "white",
  },
  realBoardScene: {
    position: "relative",
    height: "100%",
  },
  realBoardFrame: {
    position: "relative",
    height: "100%",
  },
  automationMove: {
    position: "absolute",
    zIndex: 7,
    top: 320,
    height: 54,
    pointerEvents: "none",
  },
  automationLine: {
    position: "absolute",
    left: 12,
    right: 12,
    top: "50%",
    height: 4,
    borderRadius: 999,
    background: "#10b981",
  },
  automationArrow: {
    position: "absolute",
    top: "50%",
    width: 0,
    height: 0,
    borderTop: "8px solid transparent",
    borderBottom: "8px solid transparent",
    borderLeft: `14px solid #10b981`,
  },
  automationBadge: {
    position: "absolute",
    left: "50%",
    top: -4,
    transform: "translateX(-50%)",
    padding: "8px 14px",
    borderRadius: 999,
    background: "#10b981",
    color: "white",
    fontSize: 16,
    fontWeight: 800,
    lineHeight: 1,
    whiteSpace: "nowrap",
    boxShadow: "0 8px 24px rgba(16, 185, 129, 0.4)",
  },
  cursorDrag: {
    position: "absolute",
    zIndex: 8,
    width: 48,
    height: 56,
    transform: "translate(18px, 6px)",
    filter: "drop-shadow(0 12px 24px rgba(0, 0, 0, 0.4))",
    pointerEvents: "none",
  },
  caption: {
    position: "relative",
    display: "grid",
    gridTemplateColumns: "auto 1fr",
    columnGap: 24,
    rowGap: 8,
    alignItems: "center",
    minHeight: 80,
    padding: "0 20px",
    color: "white",
    fontSize: 46,
    lineHeight: 1.15,
    fontWeight: 800,
    textAlign: "left",
  },
  captionEyebrow: {
    gridRow: "1 / span 2",
    alignSelf: "center",
    borderRadius: 999,
    padding: "12px 20px",
    background: "rgba(59, 130, 246, 0.2)",
    color: "#60a5fa",
    fontSize: 22,
    lineHeight: 1,
    fontWeight: 800,
    whiteSpace: "nowrap",
    textTransform: "uppercase",
    letterSpacing: "0.05em",
  },
  captionSubtext: {
    gridColumn: "2",
    color: "#9ca3af",
    fontSize: 28,
    lineHeight: 1.25,
    fontWeight: 500,
  },
  taskScene: {
    display: "flex",
    gap: 48,
    height: "100%",
    alignItems: "center",
  },
  phoneFrame: {
    width: 360,
    height: 620,
    padding: 12,
    borderRadius: 44,
    background: "#1e1e1e",
    border: "2px solid #333",
    boxShadow: "0 30px 80px rgba(0,0,0,0.8), inset 0 0 10px rgba(255,255,255,0.1)",
  },
  anywhereLayout: {
    display: "grid",
    gridTemplateColumns: "1fr 410px",
    gap: 44,
    height: "100%",
    alignItems: "stretch",
  },
  anywhereBoardFrame: {
    position: "relative",
    height: "100%",
  },
  anywherePhoneColumn: {
    display: "grid",
    gridTemplateRows: "1fr auto",
    gap: 30,
    justifyItems: "center",
    alignItems: "center",
  },
  anywhereNote: {
    borderRadius: 16,
    padding: "20px 24px",
    background: "rgba(255,255,255,0.05)",
    border: "1px solid rgba(255,255,255,0.1)",
    color: "#e5e7eb",
    fontSize: 24,
    lineHeight: 1.35,
    fontWeight: 600,
    textAlign: "center",
  },
  workpadLayout: {
    display: "grid",
    gridTemplateColumns: "1.35fr 0.65fr",
    gap: 40,
    height: "100%",
    alignItems: "stretch",
  },
  workpadCard: {
    borderRadius: 20,
    background: "#1e1e1e",
    padding: "50px 60px",
    boxShadow: "0 25px 80px rgba(0,0,0,0.6)",
    border: "1px solid rgba(255,255,255,0.1)",
    color: "white",
  },
  workpadHeader: {
    fontSize: 48,
    lineHeight: 1.1,
    fontWeight: 800,
    color: "white",
  },
  workpadSection: {
    marginTop: 24,
    fontSize: 20,
    color: "#60a5fa",
    fontWeight: 800,
    textTransform: "uppercase",
    letterSpacing: "0.1em",
  },
  workpadList: {
    margin: "30px 0 0",
    paddingLeft: 30,
    fontSize: 28,
    lineHeight: 1.6,
    color: "#d1d5db",
  },
  workpadContextPanel: {
    alignSelf: "stretch",
    display: "flex",
    flexDirection: "column",
    justifyContent: "center",
    borderRadius: 20,
    padding: "40px 46px",
    background: "#1e1e1e",
    border: "1px solid rgba(255,255,255,0.1)",
    boxShadow: "0 25px 80px rgba(0,0,0,0.6)",
  },
  contextPill: {
    width: "fit-content",
    borderRadius: 999,
    padding: "10px 16px",
    background: "rgba(16, 185, 129, 0.15)",
    color: "#10b981",
    fontSize: 18,
    lineHeight: 1,
    fontWeight: 800,
    textTransform: "uppercase",
    letterSpacing: "0.05em",
  },
  contextTitle: {
    margin: "28px 0 0",
    fontSize: 38,
    lineHeight: 1.15,
    color: "white",
    fontWeight: 800,
  },
  contextText: {
    margin: "20px 0 0",
    fontSize: 26,
    lineHeight: 1.35,
    color: "#9ca3af",
    fontWeight: 500,
  },
  githubFrame: {
    position: "relative",
    height: "100%",
  },
  mergeGrid: {
    display: "grid",
    gridTemplateColumns: "1fr 1fr",
    gap: 44,
    height: "100%",
  },
  finalHero: {
    padding: 74,
    background: "#080a0f",
    color: "white",
  },
  finalMedia: {
    display: "grid",
    gridTemplateColumns: "1fr 1fr",
    gap: 40,
    height: 570,
  },
  finalTile: {
    minHeight: 0,
    display: "grid",
    gridTemplateRows: "1fr auto",
    gap: 20,
  },
  finalPanel: {
    position: "relative",
    height: "100%",
  },
  finalPanelCaption: {
    color: "#9ca3af",
    fontSize: 24,
    fontWeight: 600,
    letterSpacing: 0,
    textAlign: "center",
  },
  finalCopy: {
    marginBottom: 64,
    textAlign: "center",
  },
  finalHeadline: {
    margin: "0 auto",
    maxWidth: 1560,
    fontSize: 52,
    lineHeight: 1.16,
    letterSpacing: "-0.02em",
    fontWeight: 900,
    color: "white",
  },
  finalTagline: {
    margin: "20px auto 0",
    maxWidth: 1180,
    color: "#9ca3af",
    fontSize: 32,
    lineHeight: 1.3,
    letterSpacing: 0,
  },
  label: {
    position: "absolute",
    display: "flex",
    alignItems: "center",
    gap: 10,
    padding: "12px 18px",
    borderRadius: 12,
    background: "#1e1e1e",
    border: "1px solid rgba(255,255,255,0.1)",
    color: "white",
    fontSize: 20,
    fontWeight: 700,
    boxShadow: "0 10px 30px rgba(0,0,0,0.5)",
  },
};
