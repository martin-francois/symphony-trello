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
      <Scene start={0} duration={120}>{(progress) => <Intro progress={progress} />}</Scene>
      <Scene start={120} duration={120}>{(progress) => <BoardScene image="trello-board-inbox.jpg" progress={progress} caption="Plan work where your team already plans work." activeLane="Inbox" status="New work request" detail="A real Trello card starts in the team's planning board." />}</Scene>
      <Scene start={240} duration={120}>{(progress) => <TaskAndMobile progress={progress} />}</Scene>
      <Scene start={360} duration={150}>{(progress) => <BoardScene image="trello-board-ready.jpg" progress={progress} caption="Move the card to Ready for Codex." activeLane="Ready for Codex" status="Human handoff" detail="The card is ready; no extra repo instructions are needed on the card." />}</Scene>
      <Scene start={510} duration={150}>{(progress) => <BoardScene image="trello-board-in-progress.jpg" progress={progress} caption="Symphony picks it up automatically." activeLane="In Progress" status="Automated pickup" detail="Symphony starts a Codex worker and moves the same card forward." />}</Scene>
      <Scene start={660} duration={210}>{(progress) => <WorkpadScene progress={progress} phase="initial" />}</Scene>
      <Scene start={870} duration={120}>{(progress) => <BoardScene image="trello-board-human-review-staged.jpg" progress={progress} caption="The PR is ready for review." activeLane="Human Review" status="Review handoff" detail="The card links to the pull request and waits for a reviewer." />}</Scene>
      <Scene start={990} duration={210}>{(progress) => <GithubReview progress={progress} />}</Scene>
      <Scene start={1200} duration={120}>{(progress) => <BoardScene image="trello-board-ready.jpg" progress={progress} caption="Move the card back and Codex continues." activeLane="Ready for Codex" status="Review feedback queued" detail="The reviewer asks for one small change, then returns the card to Codex." />}</Scene>
      <Scene start={1320} duration={150}>{(progress) => <BoardScene image="trello-board-in-progress.jpg" progress={progress} caption="Codex reopens the same task context." activeLane="In Progress" status="Rework running" detail="The worker reads the review thread and updates the existing PR." />}</Scene>
      <Scene start={1470} duration={210}>{(progress) => <WorkpadScene progress={progress} phase="rework" />}</Scene>
      <Scene start={1680} duration={130}>{(progress) => <BoardScene image="trello-board-human-review-staged.jpg" progress={progress} caption="Symphony brings the updated PR back for review." activeLane="Human Review" status="Updated PR" detail="The same Trello card carries the implementation, validation, and review context." />}</Scene>
      <Scene start={1810} duration={150}>{(progress) => <GithubResolved progress={progress} />}</Scene>
      <Scene start={1960} duration={140}>{(progress) => <MergeScene progress={progress} />}</Scene>
      <Scene start={2100} duration={120}>{(progress) => <BoardScene image="trello-board-done.jpg" progress={progress} caption="The card lands in Done." activeLane="Done" status="Merged and complete" detail="The pull request is merged and the Trello board shows the task as finished." />}</Scene>
      <Scene start={2220} duration={150}>{(progress) => <FinalHero progress={progress} />}</Scene>
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
      <AbsoluteFill style={{ opacity: fade(progress) }}>{children(progress)}</AbsoluteFill>
    </Sequence>
  );
}

function Intro({ progress }: { progress: number }) {
  const lift = interpolate(ease(progress), [0, 1], [24, 0]);

  return (
    <AbsoluteFill style={styles.intro}>
      <div style={{ ...styles.brandPill, transform: `translateY(${lift}px)` }}>Symphony for Trello</div>
      <h1 style={{ ...styles.title, transform: `translateY(${lift}px)` }}>
        From Trello card
        <br />
        to merged pull request
      </h1>
      <p style={styles.subtitle}>A real run: Trello plans the work, Codex implements it, and Symphony keeps the handoff moving.</p>
    </AbsoluteFill>
  );
}

function TaskAndMobile({ progress }: { progress: number }) {
  const phoneLift = interpolate(ease(progress), [0, 1], [80, 0]);
  const laptopScale = interpolate(ease(progress), [0, 1], [0.96, 1]);

  return (
    <SceneShell caption="Start from Trello on your phone or laptop.">
      <div style={styles.taskScene}>
        <div style={{ ...styles.taskLaptop, transform: `scale(${laptopScale})` }}>
          <div style={styles.taskCaptureFrame}>
            <Capture src="trello-card-task.jpg" fit="contain" radius={18} shadow={false} />
          </div>
          <div style={styles.taskSummary}>
            <span style={styles.taskSummaryLabel}>Example Trello task</span>
            <strong style={styles.taskSummaryTitle}>Clarify missing Trello token error</strong>
            <p style={styles.taskSummaryText}>Improve the missing-token error so it says what failed and which command to run next.</p>
          </div>
          <Label top={28} left={34}>Real Trello card</Label>
        </div>
        <div style={{ ...styles.phoneFrame, transform: `translateY(${phoneLift}px)` }}>
          <Capture src="trello-mobile-card.jpg" fit="contain" radius={38} shadow={false} />
        </div>
      </div>
    </SceneShell>
  );
}

function BoardScene({
  image,
  progress,
  caption,
  activeLane,
  status,
  detail,
}: {
  image: string;
  progress: number;
  caption: string;
  activeLane: string;
  status: string;
  detail: string;
}) {
  const panelLift = interpolate(ease(progress), [0, 1], [34, 0]);

  return (
    <SceneShell caption={caption}>
      <div style={styles.realBoardScene}>
        <div style={styles.realBoardFrame}>
          <Capture src={image} fit="contain" radius={24} shadow={false} />
          <div style={laneHighlight(activeLane, progress)} />
          <div style={boardTaskCard(activeLane, progress)}>
            <strong>Clarify missing Trello token error</strong>
            <span style={styles.boardTaskCardStatus}>{activeLane}</span>
          </div>
        </div>
        <div style={{ ...styles.boardInfoPanel, transform: `translateY(${panelLift}px)` }}>
          <span style={styles.boardStatusLabel}>{status}</span>
          <h3 style={styles.boardInfoTitle}>{activeLane}</h3>
          <p style={styles.boardInfoText}>{detail}</p>
        </div>
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
    <SceneShell caption={isRework ? "Codex reads the review and updates the implementation." : "Progress stays visible on the Trello card."}>
      <div style={styles.workpadLayout}>
        <div style={styles.boardPeek}>
          <Capture src="trello-board-in-progress.jpg" scale={1.14} x={-60} />
          <BoardChrome compact />
        </div>
        <WorkpadCard progress={progress} title={isRework ? "Codex Workpad - rework" : "Codex Workpad"} bullets={bullets} />
      </div>
    </SceneShell>
  );
}

function GithubReview({ progress }: { progress: number }) {
  return (
    <SceneShell caption="Review the PR and leave one clear comment.">
      <div style={styles.githubFrame}>
        <Capture
          src="github-review-diff.jpg"
          fit="contain"
          scale={interpolate(ease(progress), [0, 1], [0.98, 1])}
          shadow={false}
        />
        <Label top={30} left={34}>Real GitHub PR diff and review thread</Label>
        <Callout top={685} left={250} width={890}>The comment is tied to the actual code Codex produced.</Callout>
      </div>
    </SceneShell>
  );
}

function GithubResolved({ progress }: { progress: number }) {
  return (
    <SceneShell caption="Comment answered. Checks green.">
      <div style={styles.githubFrame}>
        <Capture
          src="github-comment-resolved.jpg"
          fit="contain"
          scale={interpolate(ease(progress), [0, 1], [0.98, 1])}
          shadow={false}
        />
        <Label top={30} left={34}>Same PR after rework</Label>
        <Callout top={705} left={270} width={980}>Codex replies with what changed and the validation it ran.</Callout>
      </div>
    </SceneShell>
  );
}

function MergeScene({ progress }: { progress: number }) {
  const boardX = interpolate(ease(progress), [0, 1], [-40, 0]);
  const githubX = interpolate(ease(progress), [0, 1], [40, 0]);

  return (
    <SceneShell caption="Move it to Merging. Symphony merges the PR.">
      <div style={styles.mergeGrid}>
        <div style={{ ...styles.panel, transform: `translateX(${boardX}px)` }}>
          <Capture src="trello-board-merging.jpg" fit="contain" shadow={false} />
          <Label top={28} left={34}>Trello: Merging</Label>
        </div>
        <div style={{ ...styles.panel, transform: `translateX(${githubX}px)` }}>
          <Capture src="github-pr-merged.jpg" fit="contain" shadow={false} />
          <Label top={28} left={34}>GitHub: merged</Label>
        </div>
      </div>
    </SceneShell>
  );
}

function FinalHero({ progress }: { progress: number }) {
  const lift = interpolate(ease(progress), [0, 1], [42, 0]);

  return (
    <AbsoluteFill style={styles.finalHero}>
      <div style={styles.finalMedia}>
        <div style={styles.finalTile}>
          <div style={styles.finalPanel}>
            <FinalTrelloSummary />
          </div>
          <div style={styles.finalPanelCaption}>Trello board</div>
        </div>
        <div style={styles.finalTile}>
          <div style={styles.finalPanel}>
            <Capture src="github-pr-merged.jpg" fit="contain" shadow={false} />
          </div>
          <div style={styles.finalPanelCaption}>Pull request: Merged</div>
        </div>
      </div>
      <div style={{ ...styles.finalCopy, transform: `translateY(${lift}px)` }}>
        <h2 style={styles.finalHeadline}>You plan work in Trello. Codex implements it. Symphony keeps everything moving.</h2>
        <p style={styles.finalTagline}>From phone to laptop, from card to merged PR. No IDE required. No CLI babysitting.</p>
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

function SceneShell({ caption, children }: { caption: string; children: ReactNode }) {
  return (
    <AbsoluteFill style={styles.scene}>
      {children}
      <Caption>{caption}</Caption>
    </AbsoluteFill>
  );
}

function Caption({ children }: { children: ReactNode }) {
  return <div style={styles.caption}>{children}</div>;
}

function Label({ children, top, left }: { children: ReactNode; top: number; left: number }) {
  return (
    <div style={{ ...styles.label, top, left }}>
      {children}
    </div>
  );
}

function Callout({ children, top, left, width }: { children: ReactNode; top: number; left: number; width: number }) {
  return <div style={{ ...styles.callout, top, left, width }}>{children}</div>;
}

function laneHighlight(activeLane: string, progress: number): CSSProperties {
  const index = Math.max(0, lanes.indexOf(activeLane));
  const laneWidth = 100 / lanes.length;

  return {
    ...styles.realBoardHighlight,
    left: `${index * laneWidth + 0.7}%`,
    width: `${laneWidth - 1.4}%`,
    opacity: interpolate(ease(progress), [0, 0.25, 1], [0, 1, 1]),
  };
}

function boardTaskCard(activeLane: string, progress: number): CSSProperties {
  const index = Math.max(0, lanes.indexOf(activeLane));
  const laneWidth = 100 / lanes.length;

  return {
    ...styles.boardTaskOverlay,
    left: `${index * laneWidth + 2.1}%`,
    width: `${laneWidth - 4.2}%`,
    opacity: interpolate(ease(progress), [0, 0.2, 1], [0, 1, 1]),
    transform: `translateY(${interpolate(ease(progress), [0, 1], [28, 0])}px)`,
  };
}

function BoardChrome({ compact = false }: { compact?: boolean }) {
  return (
    <div style={{ ...styles.boardChrome, height: compact ? 82 : 96 }}>
      <strong>Symphony for Trello - Demo</strong>
      <span>Inbox</span>
      <span>Ready for Codex</span>
      <span>In Progress</span>
      <span>Human Review</span>
      <span>Merging</span>
      <span>Done</span>
    </div>
  );
}

function fade(progress: number) {
  return interpolate(progress, [0, 0.08, 0.92, 1], [0, 1, 1, 0]);
}

const styles: Record<string, CSSProperties> = {
  stage: {
    background: `linear-gradient(135deg, ${deep} 0%, #0b4d6c 46%, #eaf7fb 100%)`,
    color: ink,
    fontFamily:
      'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    overflow: "hidden",
  },
  scene: {
    padding: 74,
    background: "linear-gradient(135deg, #eaf7fb 0%, #f8fafc 44%, #eaf1ff 100%)",
  },
  intro: {
    justifyContent: "center",
    padding: "0 150px",
    background: `radial-gradient(circle at 76% 18%, rgba(22, 163, 74, 0.24), transparent 28%), linear-gradient(135deg, ${deep}, ${blue})`,
    color: "white",
  },
  brandPill: {
    width: "fit-content",
    border: "1px solid rgba(255,255,255,0.34)",
    borderRadius: 999,
    padding: "16px 26px",
    fontSize: 28,
    letterSpacing: 0,
    background: "rgba(255,255,255,0.12)",
    backdropFilter: "blur(12px)",
  },
  title: {
    margin: "38px 0 0",
    fontSize: 108,
    lineHeight: 1.02,
    letterSpacing: 0,
    maxWidth: 1200,
  },
  subtitle: {
    marginTop: 30,
    maxWidth: 980,
    fontSize: 34,
    lineHeight: 1.35,
    color: "rgba(255,255,255,0.84)",
  },
  browserCard: {
    position: "relative",
    height: 820,
    borderRadius: 28,
    overflow: "hidden",
    background: "white",
    border: "1px solid rgba(15, 107, 154, 0.18)",
    boxShadow: "0 34px 90px rgba(8, 40, 58, 0.22)",
  },
  realBoardScene: {
    position: "relative",
    height: 820,
    display: "grid",
    gridTemplateRows: "1fr auto",
    gap: 24,
  },
  realBoardFrame: {
    position: "relative",
    minHeight: 0,
    borderRadius: 28,
    overflow: "hidden",
    background: "white",
    border: "1px solid rgba(15, 107, 154, 0.16)",
    boxShadow: "0 34px 90px rgba(8, 40, 58, 0.2)",
  },
  realBoardHighlight: {
    position: "absolute",
    top: 0,
    bottom: 0,
    borderRadius: 22,
    border: `5px solid ${green}`,
    background: "rgba(22, 163, 74, 0.08)",
    boxShadow: "0 20px 48px rgba(22, 163, 74, 0.3)",
    pointerEvents: "none",
  },
  boardTaskOverlay: {
    position: "absolute",
    top: 94,
    borderRadius: 14,
    padding: "16px 16px 14px",
    background: "white",
    color: ink,
    fontSize: 22,
    lineHeight: 1.18,
    boxShadow: "0 18px 42px rgba(8, 40, 58, 0.28)",
    border: "1px solid rgba(8, 40, 58, 0.12)",
  },
  boardTaskCardStatus: {
    display: "block",
    marginTop: 10,
    color: blue,
    fontSize: 18,
    fontWeight: 800,
  },
  boardInfoPanel: {
    display: "grid",
    gridTemplateColumns: "auto auto 1fr",
    gap: 22,
    alignItems: "center",
    borderRadius: 24,
    padding: "22px 30px",
    background: "rgba(8, 40, 58, 0.94)",
    color: "white",
    boxShadow: "0 24px 58px rgba(8, 40, 58, 0.22)",
  },
  boardInfoTitle: {
    margin: 0,
    color: "white",
    fontSize: 36,
    lineHeight: 1.1,
    letterSpacing: 0,
  },
  boardInfoText: {
    margin: 0,
    color: "rgba(255,255,255,0.9)",
    fontSize: 28,
    lineHeight: 1.25,
    fontWeight: 680,
  },
  boardChrome: {
    position: "absolute",
    top: 0,
    left: 0,
    right: 0,
    display: "flex",
    gap: 24,
    alignItems: "center",
    padding: "0 32px",
    background: "linear-gradient(90deg, rgba(8,40,58,0.96), rgba(15,107,154,0.92))",
    color: "white",
    fontSize: 22,
  },
  boardOverlay: {
    position: "absolute",
    inset: "132px 46px 46px",
    display: "grid",
    gridTemplateRows: "1fr auto",
    gap: 34,
  },
  laneStrip: {
    display: "grid",
    gridTemplateColumns: "repeat(6, 1fr)",
    gap: 18,
    alignItems: "stretch",
  },
  laneCard: {
    borderRadius: 24,
    padding: "22px 18px",
    background: "rgba(255,255,255,0.7)",
    border: "1px solid rgba(15, 107, 154, 0.16)",
    boxShadow: "0 18px 44px rgba(8, 40, 58, 0.12)",
  },
  activeLaneCard: {
    background: "rgba(255,255,255,0.96)",
    border: "3px solid rgba(22, 163, 74, 0.78)",
    boxShadow: "0 26px 68px rgba(8, 40, 58, 0.24)",
  },
  laneTitle: {
    minHeight: 72,
    color: "rgba(8, 40, 58, 0.58)",
    fontSize: 25,
    lineHeight: 1.15,
    fontWeight: 780,
  },
  activeLaneTitle: {
    color: deep,
  },
  taskCard: {
    marginTop: 26,
    borderRadius: 18,
    padding: "22px 20px",
    background: "white",
    border: "1px solid rgba(8, 40, 58, 0.1)",
    boxShadow: "0 20px 48px rgba(8, 40, 58, 0.2)",
    fontSize: 26,
    lineHeight: 1.24,
  },
  emptyLaneHint: {
    marginTop: 34,
    height: 84,
    borderRadius: 16,
    background: "rgba(15, 107, 154, 0.08)",
  },
  boardStatus: {
    display: "flex",
    alignItems: "center",
    gap: 26,
    borderRadius: 22,
    padding: "24px 30px",
    background: "rgba(8, 40, 58, 0.9)",
    color: "white",
    fontSize: 30,
    lineHeight: 1.25,
    boxShadow: "0 24px 58px rgba(8, 40, 58, 0.22)",
  },
  taskCardStatus: {
    display: "block",
    marginTop: 12,
    color: blue,
    fontSize: 22,
    fontWeight: 760,
  },
  boardStatusLabel: {
    flexShrink: 0,
    borderRadius: 999,
    padding: "12px 18px",
    background: `linear-gradient(135deg, ${green}, #22c55e)`,
    fontSize: 24,
    fontWeight: 820,
  },
  boardStatusText: {
    margin: 0,
    fontWeight: 700,
  },
  caption: {
    position: "absolute",
    left: 120,
    right: 120,
    bottom: 44,
    padding: "18px 30px",
    borderRadius: 18,
    background: "rgba(8, 40, 58, 0.86)",
    color: "white",
    fontSize: 38,
    lineHeight: 1.18,
    fontWeight: 720,
    textAlign: "center",
    boxShadow: "0 22px 52px rgba(8, 40, 58, 0.25)",
  },
  taskScene: {
    display: "flex",
    gap: 48,
    height: 800,
    alignItems: "center",
  },
  taskLaptop: {
    position: "relative",
    flex: 1.28,
    height: "100%",
    minWidth: 0,
    display: "grid",
    gridTemplateRows: "1fr auto",
    gap: 24,
    borderRadius: 28,
    overflow: "hidden",
    background: "white",
    boxShadow: "0 34px 90px rgba(8, 40, 58, 0.2)",
  },
  taskCaptureFrame: {
    minHeight: 0,
    padding: "76px 30px 0",
    background: "#f8fafc",
  },
  taskSummary: {
    padding: "26px 42px 34px",
    borderTop: "1px solid rgba(15, 107, 154, 0.14)",
    background: "white",
    color: deep,
  },
  taskSummaryLabel: {
    display: "block",
    color: blue,
    fontSize: 24,
    fontWeight: 760,
  },
  taskSummaryTitle: {
    display: "block",
    marginTop: 10,
    fontSize: 42,
    lineHeight: 1.08,
  },
  taskSummaryText: {
    margin: "14px 0 0",
    fontSize: 29,
    lineHeight: 1.28,
    fontWeight: 680,
  },
  panel: {
    position: "relative",
    height: "100%",
    minWidth: 0,
    borderRadius: 28,
    overflow: "hidden",
    background: "white",
    boxShadow: "0 34px 90px rgba(8, 40, 58, 0.2)",
  },
  phoneFrame: {
    width: 380,
    height: 750,
    padding: 16,
    borderRadius: 54,
    background: ink,
    boxShadow: "0 30px 80px rgba(8, 40, 58, 0.34)",
  },
  workpadLayout: {
    display: "grid",
    gridTemplateColumns: "0.98fr 1.05fr",
    gap: 44,
    height: 820,
    alignItems: "stretch",
  },
  boardPeek: {
    position: "relative",
    borderRadius: 28,
    overflow: "hidden",
    background: "white",
    boxShadow: "0 34px 90px rgba(8, 40, 58, 0.18)",
  },
  workpadCard: {
    borderRadius: 28,
    background: "white",
    padding: "56px 64px",
    boxShadow: "0 34px 90px rgba(8, 40, 58, 0.22)",
    border: "1px solid rgba(15, 107, 154, 0.16)",
  },
  workpadHeader: {
    fontSize: 50,
    lineHeight: 1.1,
    fontWeight: 760,
    color: deep,
  },
  workpadSection: {
    marginTop: 28,
    fontSize: 30,
    color: blue,
    fontWeight: 760,
  },
  workpadList: {
    margin: "34px 0 0",
    paddingLeft: 34,
    fontSize: 31,
    lineHeight: 1.5,
  },
  githubFrame: {
    position: "relative",
    height: 820,
    borderRadius: 28,
    overflow: "hidden",
    background: "white",
    boxShadow: "0 34px 90px rgba(8, 40, 58, 0.22)",
  },
  mergeGrid: {
    display: "grid",
    gridTemplateColumns: "1fr 1fr",
    gap: 44,
    height: 820,
  },
  finalHero: {
    padding: 74,
    background: `linear-gradient(135deg, ${deep}, #0f5f85 56%, #eaf7fb)`,
    color: "white",
  },
  finalMedia: {
    display: "grid",
    gridTemplateColumns: "1fr 1fr",
    gap: 36,
    height: 570,
  },
  finalTile: {
    minHeight: 0,
    display: "grid",
    gridTemplateRows: "1fr auto",
    gap: 16,
  },
  finalPanel: {
    position: "relative",
    borderRadius: 28,
    overflow: "hidden",
    background: "white",
    boxShadow: "0 34px 90px rgba(0,0,0,0.28)",
  },
  finalTrelloSummary: {
    height: "100%",
    padding: 28,
    background: "linear-gradient(135deg, #f1f8fb, #ffffff)",
  },
  finalMiniBoard: {
    height: "100%",
    display: "grid",
    gridTemplateColumns: "repeat(5, 0.82fr) 1.34fr",
    gap: 12,
  },
  finalMiniLane: {
    minWidth: 0,
    borderRadius: 18,
    padding: "18px 12px",
    background: "rgba(15, 107, 154, 0.09)",
    border: "1px solid rgba(15, 107, 154, 0.14)",
  },
  finalMiniDoneLane: {
    background: "rgba(22, 163, 74, 0.14)",
    border: `3px solid ${green}`,
    boxShadow: "0 18px 42px rgba(22, 163, 74, 0.18)",
  },
  finalMiniLaneTitle: {
    display: "block",
    color: deep,
    fontSize: 20,
    lineHeight: 1.1,
    fontWeight: 800,
  },
  finalMiniTaskCard: {
    marginTop: 18,
    borderRadius: 14,
    padding: "16px 14px",
    background: "white",
    color: deep,
    fontSize: 20,
    lineHeight: 1.18,
    boxShadow: "0 16px 34px rgba(8, 40, 58, 0.14)",
  },
  finalMiniStatus: {
    display: "block",
    marginTop: 10,
    color: green,
    fontSize: 18,
    fontWeight: 820,
  },
  finalMiniEmptyCard: {
    marginTop: 22,
    height: 68,
    borderRadius: 14,
    background: "rgba(255,255,255,0.58)",
  },
  finalPanelCaption: {
    color: "white",
    fontSize: 26,
    fontWeight: 760,
    letterSpacing: 0,
    textAlign: "center",
  },
  finalCopy: {
    marginTop: 54,
    textAlign: "center",
  },
  finalHeadline: {
    margin: "0 auto",
    maxWidth: 1560,
    color: "white",
    fontSize: 48,
    lineHeight: 1.16,
    letterSpacing: 0,
  },
  finalTagline: {
    margin: "22px auto 0",
    maxWidth: 1180,
    color: "rgba(255,255,255,0.9)",
    fontSize: 30,
    lineHeight: 1.3,
    letterSpacing: 0,
  },
  label: {
    position: "absolute",
    display: "flex",
    alignItems: "center",
    gap: 12,
    padding: "14px 18px",
    borderRadius: 999,
    background: "rgba(255,255,255,0.94)",
    color: deep,
    fontSize: 24,
    fontWeight: 760,
    boxShadow: "0 14px 34px rgba(8, 40, 58, 0.16)",
  },
  callout: {
    position: "absolute",
    padding: "20px 24px",
    borderRadius: 18,
    background: `linear-gradient(135deg, ${green}, #22c55e)`,
    color: "white",
    fontSize: 28,
    lineHeight: 1.25,
    fontWeight: 760,
    boxShadow: "0 18px 46px rgba(22, 163, 74, 0.26)",
  },
};
