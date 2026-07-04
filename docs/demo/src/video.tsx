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
      <Scene start={2080} duration={140}>
        {(progress) => (
          <BoardScene
            image="trello-board-merging.jpg"
            progress={progress}
            caption="Move the card to Merging."
            activeLane="Merging"
            fromLane="Human Review"
            showCursor
            status="Trigger merge"
            detail="Dropping the card here tells Symphony to merge the code."
          />
        )}
      </Scene>
      <Scene start={2220} duration={140}>{(progress) => <GithubMergedScene progress={progress} />}</Scene>
      <Scene start={2360} duration={120}>{(progress) => <BoardScene image="trello-board-done.jpg" progress={progress} caption="The card lands in Done." activeLane="Done" fromLane="Merging" status="Merged and complete" detail="The PR is merged and the board shows the task as finished." />}</Scene>
      <Scene start={2480} duration={130}>{(progress) => <AnywhereScene progress={progress} />}</Scene>
      <Scene start={2610} duration={150}>{(progress) => <FinalHero progress={progress} />}</Scene>
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
  const lift = interpolate(ease(progress), [0, 1], [24, 0]);

  return (
    <AbsoluteFill style={styles.intro}>
      <div style={{ ...styles.brandPill, translate: `0 ${lift}px` }}>Symphony for Trello</div>
      <h1 style={{ ...styles.title, translate: `0 ${lift}px` }}>
        From Trello card
        <br />
        to merged pull request
      </h1>
      <p style={styles.subtitle}>A real run: Trello plans the work, Codex implements it, and Symphony keeps the handoff moving.</p>
    </AbsoluteFill>
  );
}

function AnywhereScene({ progress }: { progress: number }) {
  const phoneLift = interpolate(ease(progress), [0, 1], [80, 0]);
  const boardScale = interpolate(ease(progress), [0, 1], [0.96, 1]);

  return (
    <SceneShell
      caption="The same workflow works from phone or laptop."
      subcaption="The board stays familiar: plan, review, and track work where your team already looks."
    >
      <div style={styles.anywhereLayout}>
        <MacWindow style={{ ...styles.anywhereBoardFrame, scale: boardScale }}>
          <Capture src="trello-board-done.jpg" fit="cover" radius={24} shadow={false} style={styles.boardBackgroundCapture} />
        </MacWindow>
        <div style={styles.anywherePhoneColumn}>
          <div style={{ ...styles.phoneFrame, translate: `0 ${phoneLift}px` }}>
            <Capture src="trello-mobile-card.jpg" fit="contain" radius={38} shadow={false} />
          </div>
          <div style={styles.anywhereNote}>Plan, review, and track the same work from anywhere.</div>
        </div>
      </div>
    </SceneShell>
  );
}


function MacWindow({ children, style, title }: { children: React.ReactNode; style?: React.CSSProperties; title?: string }) {
  return (
    <div style={{ ...styles.macWindow, ...style }}>
      <div style={styles.macChrome}>
        <div style={{ display: "flex", gap: 8, width: 60 }}>
          <div style={{ ...styles.macDot, background: "#ff5f56", border: "1px solid #e0443e" }} />
          <div style={{ ...styles.macDot, background: "#ffbd2e", border: "1px solid #dea123" }} />
          <div style={{ ...styles.macDot, background: "#27c93f", border: "1px solid #1aab29" }} />
        </div>
        {title && <div style={styles.macTitle}>{title}</div>}
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

  return (
    <SceneShell caption={caption} eyebrow={status} subcaption={detail}>
      <div style={styles.realBoardScene}>
        <MacWindow style={styles.realBoardFrame}>
          <Capture src={image} fit="cover" shadow={false} style={styles.boardBackgroundCapture} />
          <StoryBoard activeLane={activeLane} fromLane={fromLane} moving={moving} progress={progress} />
          {showCursor && fromLane ? <CursorDrag fromLane={fromLane} toLane={activeLane} progress={progress} /> : null}
        </MacWindow>
      </div>
    </SceneShell>
  );
}

function GhostPatch({ lane }: { lane: string }) {
  return (
    <div style={{
      position: "absolute",
      left: `${laneCenter(lane)}%`,
      top: 92,
      translate: "-50% 0",
      width: `calc(${laneWidth()}% - 22px)`,
      height: 94,
      background: "#f1f2f4",
      borderRadius: 8,
      zIndex: 2,
    }} />
  );
}

function MovingStoryCard({ fromLane, toLane, progress }: { fromLane: string; toLane: string; progress: number }) {
  const left = interpolate(dragProgress(progress), [0, 1], [laneCenter(fromLane), laneCenter(toLane)]);
  const top = interpolate(dragProgress(progress), [0, 1], [92, 92]);

  return (
    <div style={{
      position: "absolute",
      zIndex: 6,
      left: `${left}%`,
      top,
      translate: "-50% 0",
      width: `calc(${laneWidth()}% - 22px)`,
      borderRadius: 12,
      padding: "18px 16px",
      background: "white",
      border: "1px solid rgba(8, 40, 58, 0.12)",
      boxShadow: "0 18px 42px rgba(8, 40, 58, 0.28)",
      color: ink,
      fontSize: 22,
      lineHeight: 1.18,
    }}>
      <strong>Clarify missing Trello token error</strong>
      <span style={{
        display: "block",
        marginTop: 10,
        color: blue,
        fontSize: 18,
        fontWeight: 800,
      }}>Moving</span>
    </div>
  );
}

function StoryBoard({ activeLane, fromLane, moving, progress }: { activeLane: string; fromLane?: string; moving: boolean; progress: number }) {
  if (!moving || !fromLane) return null;
  return (
    <div style={{ position: "absolute", inset: 0, pointerEvents: "none" }}>
      <GhostPatch lane={fromLane} />
      <GhostPatch lane={activeLane} />
      <MovingStoryCard fromLane={fromLane} toLane={activeLane} progress={progress} />
    </div>
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

  if (isRework) {
    return (
      <SceneShell
        caption="Codex reads the review and updates the implementation."
        subcaption="The rework starts from the review comment and keeps the same PR alive."
      >
        <div style={styles.workpadLayout}>
          <WorkpadCard progress={progress} title="Codex Workpad - rework" bullets={bullets} />
          <div style={styles.workpadContextPanel}>
            <h3 style={styles.contextTitle}>Review feedback is being handled</h3>
            <p style={styles.contextText}>Codex keeps the same card and PR, then records what changed.</p>
          </div>
        </div>
      </SceneShell>
    );
  }

  return (
    <SceneShell
      caption="Progress stays visible on the Trello card."
      subcaption="The workpad shows you what Codex is doing before the PR is ready."
    >
      <div style={styles.workpadLayout}>
        <div style={styles.workpadContextPanel}>
          <h3 style={styles.contextTitle}>Implementation is in progress</h3>
          <p style={styles.contextText}>
            You can see the plan, progress, and validation without opening host logs.
          </p>
        </div>
        <MacWindow style={{ height: "100%" }} title="codex-worker — bash">
          <div style={{ padding: "40px 50px" }}>
            <div style={styles.workpadHeader}>Codex Workpad</div>
            <div style={styles.workpadSection}>Plan / Progress / Validation</div>
            <ul style={styles.workpadList}>
              {bullets.map((b, i) => (
                <li key={i} style={{ marginBottom: 12, opacity: progress > (i * 0.1) ? 1 : 0 }}>{b}</li>
              ))}
            </ul>
          </div>
        </MacWindow>
      </div>
    </SceneShell>
  );
}

function GithubReview({ progress }: { progress: number }) {
  return (
    <SceneShell caption="Review the PR, leaving comments as usual.">
      <div style={styles.githubFrame}>
        <Capture
          src="github-review-diff.jpg"
          fit="cover"
          scale={interpolate(ease(progress), [0, 1], [1.35, 1.45])}
          shadow={false}
          style={{ objectPosition: "50% 42%" }}
        />
        <Label bottom={128} left={34}>Real GitHub PR diff and review thread</Label>
        <Callout bottom={34} left={34} width={910}>The comment is tied to the actual code Codex produced.</Callout>
      </div>
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
          scale={interpolate(ease(progress), [0, 1], [1.35, 1.45])}
          shadow={false}
          style={{ objectPosition: "50% 70%" }}
        />
        <Label bottom={128} left={34}>Same PR after rework</Label>
        <Callout bottom={34} left={34} width={980}>Codex replies with what changed and the validation it ran.</Callout>
      </MacWindow>
    </SceneShell>
  );
}

function GithubMergedScene({ progress }: { progress: number }) {
  return (
    <SceneShell caption="Symphony merges the pull request automatically.">
      <MacWindow style={{...styles.realBoardFrame, scale: interpolate(ease(progress), [0, 1], [0.95, 1])}}>
        <Capture src="github-pr-merged.jpg" fit="cover" shadow={false} style={{ objectPosition: "left top" }} />
      </MacWindow>
    </SceneShell>
  );
}

function MergeScene({ progress }: { progress: number }) {
  const boardX = interpolate(ease(progress), [0, 1], [-40, 0]);
  const githubX = interpolate(ease(progress), [0, 1], [40, 0]);

  return (
    <SceneShell caption="Symphony merges the pull request automatically." subcaption="Trello remains the control surface while GitHub records the merged code.">
      <div style={styles.mergeGrid}>
        <MacWindow style={{ ...styles.panel, translate: `${boardX}px 0` }}>
          <Capture src="trello-board-merging.jpg" fit="contain" shadow={false} />
          <Label bottom={34} left={34}>Trello: Merging</Label>
        </MacWindow>
        <MacWindow style={{ ...styles.panel, translate: `${githubX}px 0` }}>
          <Capture src="github-pr-merged.jpg" fit="contain" shadow={false} />
          <Label bottom={34} left={34}>GitHub: Merged</Label>
        </MacWindow>
      </div>
    </SceneShell>
  );
}

function FinalHero({ progress }: { progress: number }) {
  return (
    <AbsoluteFill style={{ ...styles.scene, opacity: ease(progress), justifyContent: "center" }}>
      <div style={styles.finalLayout}>
        <h2 style={styles.finalHeadline}>You plan work in Trello. Codex implements it. Symphony keeps everything moving.</h2>
        <p style={styles.finalSubtext}>From phone to laptop, from card to merged PR.<br/><strong style={{color: green}}>No IDE required. No CLI babysitting.</strong></p>
      </div>
      <div style={styles.finalMedia}>
        <div style={styles.finalTile}>
          <MacWindow style={styles.finalPanel}>
            <Capture src="trello-board-done.jpg" fit="cover" shadow={false} style={{ objectPosition: "left top" }} />
          </MacWindow>
          <div style={styles.finalPanelCaption}>Trello: Done</div>
        </div>
        <div style={styles.finalTile}>
          <MacWindow style={styles.finalPanel}>
            <Capture src="github-pr-merged.jpg" fit="contain" shadow={false} />
          </MacWindow>
          <div style={styles.finalPanelCaption}>Pull request: Merged</div>
        </div>
      </div>
    </AbsoluteFill>
  );
}

function FinalTrelloSummary() {
  return (
    <div style={styles.finalTrelloSummary}>
      <div style={styles.finalMiniBoard}>
        {lanes.map((lane) => {
          const done = lane === "Done";

          return (
            <div key={lane} style={{ ...styles.finalMiniLane, ...(done ? styles.finalMiniDoneLane : {}) }}>
              <span style={styles.finalMiniLaneTitle}>{lane}</span>
              {done ? (
                <div style={styles.finalMiniTaskCard}>
                  <strong>Clarify missing Trello token error</strong>
                  <span style={styles.finalMiniStatus}>Merged PR</span>
                </div>
              ) : (
                <div style={styles.finalMiniEmptyCard} />
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function WorkpadCard({ progress, title, bullets }: { progress: number; title: string; bullets: string[] }) {
  return (
    <div style={styles.workpadCard}>
      <div style={styles.workpadHeader}>{title}</div>
      <div style={styles.workpadSection}>Plan / Progress / Validation</div>
      <ul style={styles.workpadList}>
        {bullets.map((bullet, index) => {
          const visible = clamp((progress - index * 0.13) / 0.28);

          return (
            <li
              key={bullet}
              style={{
                opacity: visible,
                translate: `0 ${interpolate(ease(visible), [0, 1], [18, 0])}px`,
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
  const isTrello = src.startsWith("trello");
  
  const finalFilter = style?.filter || "";

  return (
    <Img
      src={staticFile(`captures/${src}`)}
      style={{
        width: "100%",
        height: "100%",
        objectFit: fit,
        borderRadius: radius,
        boxShadow: shadow ? "0 28px 80px rgba(8, 40, 58, 0.28)" : undefined,
        translate: `${x}px ${y}px`,
        scale,
        transformOrigin: "center center",
        ...style,
        filter: finalFilter || undefined,
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
      <strong style={{ gridColumn: "1 / -1" }}>{children}</strong>
      {subcaption ? (
        <span style={{ ...styles.captionSubtext, gridColumn: "1 / -1" }}>{subcaption}</span>
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

function CursorDrag({ fromLane, toLane, progress }: { fromLane: string; toLane: string; progress: number }) {
  const left = interpolate(dragProgress(progress), [0, 1], [laneCenter(fromLane), laneCenter(toLane)]);
  const top = interpolate(dragProgress(progress), [0, 1], [140, 150]);

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
        <path d="M29 60L46 80" stroke="white" strokeWidth="10" strokeLinecap="round" />
        <path d="M30 60L45 78" stroke={deep} strokeWidth="6" strokeLinecap="round" />
      </svg>
    </div>
  );
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
    background: "#f8fafc",
    color: ink,
    fontFamily:
      'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    overflow: "hidden",
  },
  macWindow: {
    display: "flex",
    flexDirection: "column",
    borderRadius: 14,
    overflow: "hidden",
    background: "white",
    border: "1px solid rgba(0,0,0,0.25)",
    boxShadow: "0 25px 80px rgba(0, 0, 0, 0.3)",
    width: "100%",
  },
  macChrome: {
    height: 44,
    background: "#f1f5f9",
    display: "flex",
    alignItems: "center",
    padding: "0 16px",
    borderBottom: "1px solid rgba(0,0,0,0.05)",
    position: "relative",
  },
  macDot: {
    width: 12,
    height: 12,
    borderRadius: "50%",
  },
  macTitle: {
    position: "absolute",
    left: "50%",
    translate: "-50% 0",
    color: "#64748b",
    fontSize: 15,
    fontWeight: 600,
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace",
  },
  macContent: {
    flex: 1,
    position: "relative",
    minHeight: 0,
    background: "white",
  },
  scene: {
    display: "grid",
    gridTemplateRows: "auto 1fr",
    gap: 30,
    padding: "40px 60px 50px",
    background: "#f8fafc",
  },
  sceneContent: {
    position: "relative",
    minHeight: 0,
  },
  intro: {
    justifyContent: "center",
    padding: "0 150px",
    background: "#f8fafc",
    color: deep,
  },
  brandPill: {
    width: "fit-content",
    border: "1px solid rgba(8, 40, 58, 0.15)",
    borderRadius: 12,
    padding: "12px 24px",
    fontSize: 24,
    letterSpacing: 0,
    background: "white",
    fontWeight: 600,
    boxShadow: "0 4px 12px rgba(8, 40, 58, 0.05)",
  },
  title: {
    margin: "38px 0 0",
    fontSize: 96,
    lineHeight: 1.05,
    letterSpacing: "-0.02em",
    fontWeight: 800,
    maxWidth: 1200,
  },
  subtitle: {
    marginTop: 30,
    maxWidth: 980,
    fontSize: 32,
    lineHeight: 1.35,
    color: "rgba(8, 40, 58, 0.6)",
    fontWeight: 400,
  },
  realBoardScene: {
    position: "relative",
    height: "100%",
  },
  realBoardFrame: {
    position: "relative",
    height: "100%",
    minHeight: 0,
    borderRadius: 20,
    overflow: "hidden",
    background: "white",
    border: "1px solid rgba(8, 40, 58, 0.25)",
    boxShadow: "0 20px 60px rgba(8, 40, 58, 0.25)",
  },
  boardBackgroundCapture: {
    opacity: 1,
  },
  storyBoard: {
    position: "absolute",
    inset: "52px 22px 42px",
    display: "grid",
    gridTemplateColumns: "repeat(6, 1fr)",
    gap: 16,
  },
  cursorDrag: {
    position: "absolute",
    zIndex: 8,
    width: 48,
    height: 56,
    translate: "18px 6px",
    filter: "drop-shadow(0 8px 12px rgba(8, 40, 58, 0.2))",
    pointerEvents: "none",
  },
  phoneFrame: {
    width: 360,
    height: 620,
    padding: 16,
    borderRadius: 44,
    background: "#1e1e1e",
    boxShadow: "0 20px 60px rgba(8, 40, 58, 0.15)",
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
    minWidth: 0,
    borderRadius: 20,
    overflow: "hidden",
    background: "white",
    border: "1px solid rgba(8, 40, 58, 0.1)",
    boxShadow: "0 20px 60px rgba(8, 40, 58, 0.08)",
  },
  anywherePhoneColumn: {
    display: "grid",
    gridTemplateRows: "1fr auto",
    gap: 22,
    justifyItems: "center",
    alignItems: "center",
  },
  anywhereNote: {
    borderRadius: 16,
    padding: "20px 24px",
    background: "white",
    border: "1px solid rgba(8, 40, 58, 0.1)",
    color: deep,
    fontSize: 22,
    lineHeight: 1.35,
    fontWeight: 600,
    textAlign: "center",
    boxShadow: "0 10px 30px rgba(8, 40, 58, 0.05)",
  },
  workpadLayout: {
    display: "grid",
    gridTemplateColumns: "0.65fr 1.35fr",
    gap: 38,
    height: "100%",
    alignItems: "stretch",
  },
  workpadCard: {
    borderRadius: 20,
    background: "white",
    padding: "50px 60px",
    boxShadow: "0 15px 50px rgba(8, 40, 58, 0.05)",
    border: "1px solid rgba(8, 40, 58, 0.1)",
  },
  workpadHeader: {
    fontSize: 64,
    lineHeight: 1.1,
    fontWeight: 700,
    color: "#0f172a",
  },
  workpadSection: {
    marginTop: 24,
    fontSize: 24,
    color: "rgba(8, 40, 58, 0.6)",
    fontWeight: 600,
    textTransform: "uppercase",
    letterSpacing: "0.05em",
  },
  workpadList: {
    listStyle: "none",
    padding: 0,
    margin: 0,
    fontSize: 22,
    lineHeight: 1.6,
    color: ink,
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace",
  },
  workpadContextPanel: {
    width: 320,
    display: "flex",
    flexDirection: "column",
    justifyContent: "flex-start",
    padding: "40px 20px",
  },
  contextTitle: {
    margin: "28px 0 0",
    fontSize: 38,
    lineHeight: 1.15,
    color: deep,
  },
  contextText: {
    margin: "20px 0 0",
    fontSize: 26,
    lineHeight: 1.35,
    color: "rgba(8, 40, 58, 0.7)",
    fontWeight: 500,
  },
  githubFrame: {
    position: "relative",
    width: 1400,
    height: 720,
    margin: "0 auto",
  },
  mergeGrid: {
    display: "grid",
    gridTemplateColumns: "1fr 1fr",
    gap: 44,
    height: "100%",
  },
  finalLayout: {
    textAlign: "center",
  },
  finalMedia: {
    display: "grid",
    gridTemplateColumns: "1fr 1fr",
    gap: 36,
    height: 570,
    marginTop: 60,
  },
  finalTile: {
    minHeight: 0,
    display: "grid",
    gridTemplateRows: "1fr auto",
    gap: 16,
  },
  finalPanel: {
    position: "absolute",
    inset: 0,
    borderRadius: 20,
    overflow: "hidden",
    background: "white",
    border: "1px solid rgba(8, 40, 58, 0.25)",
    boxShadow: "0 20px 60px rgba(8, 40, 58, 0.25)",
  },
  finalTrelloSummary: {
    height: "100%",
    padding: 28,
    background: "white",
  },
  finalMiniBoard: {
    height: "100%",
    display: "grid",
    gridTemplateColumns: "repeat(5, 0.82fr) 1.34fr",
    gap: 12,
  },
  finalMiniLane: {
    minWidth: 0,
    borderRadius: 12,
    padding: "16px 12px",
    background: "#f5f7f9",
    border: "1px solid rgba(8, 40, 58, 0.05)",
  },
  finalMiniDoneLane: {
    background: "rgba(22, 163, 74, 0.05)",
    border: `2px solid ${green}`,
  },
  finalMiniLaneTitle: {
    display: "block",
    color: deep,
    fontSize: 18,
    lineHeight: 1.1,
    fontWeight: 700,
  },
  finalMiniTaskCard: {
    marginTop: 16,
    borderRadius: 10,
    padding: "14px 12px",
    background: "white",
    color: deep,
    fontSize: 18,
    lineHeight: 1.2,
    boxShadow: "0 8px 20px rgba(8, 40, 58, 0.08)",
    border: "1px solid rgba(8, 40, 58, 0.1)",
  },
  finalMiniStatus: {
    display: "block",
    marginTop: 8,
    color: green,
    fontSize: 16,
    fontWeight: 700,
  },
  finalMiniEmptyCard: {
    marginTop: 20,
    height: 60,
    borderRadius: 10,
    background: "rgba(8, 40, 58, 0.03)",
  },
  finalPanelCaption: {
    color: "rgba(8, 40, 58, 0.6)",
    fontSize: 32,
    fontWeight: 700,
    letterSpacing: 0,
    textAlign: "center",
  },
  finalHeadline: {
    margin: "0 auto",
    maxWidth: 1560,
    color: deep,
    fontSize: 48,
    lineHeight: 1.16,
    letterSpacing: 0,
    fontWeight: 800,
  },
  finalSubtext: {
    margin: "20px auto 0",
    maxWidth: 1180,
    color: "rgba(8, 40, 58, 0.7)",
    fontSize: 30,
    lineHeight: 1.3,
    letterSpacing: 0,
  },
  label: {
    position: "absolute",
    display: "flex",
    alignItems: "center",
    gap: 10,
    padding: "16px 24px",
    borderRadius: 12,
    background: "white",
    border: "1px solid rgba(8, 40, 58, 0.1)",
    color: deep,
    fontSize: 28,
    fontWeight: 700,
    boxShadow: "0 10px 30px rgba(8, 40, 58, 0.15)",
  },
  callout: {
    position: "absolute",
    padding: "14px 18px",
    borderRadius: 12,
    background: deep,
    color: "white",
    fontSize: 22,
    lineHeight: 1.3,
    fontWeight: 500,
    boxShadow: "0 12px 35px rgba(8, 40, 58, 0.15)",
  },
};
