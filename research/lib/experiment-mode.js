'use strict'

const { GATE_GENERATION } = require('./behavior-gate.js')

const CRASH_CONTAINMENT_MODE = 'crash-containment'
const ROUTINE_V12_PRIMARY_BEHAVIOR_MODE = 'routine-v12-primary-behavior'
const ROUTINE_V13_GOAL_CONTINUITY_MODE = 'routine-v13-goal-continuity'
// v14 differs from v13 in one affordance: a walk names an observed block by ref rather than a
// coordinate the perception never gave, so the two are separate treatments.
const ROUTINE_V14_GROUNDED_WALK_MODE = 'routine-v14-grounded-walk'
// v15 keeps v14's ref-named walk and states where it ends for a block a body walks through:
// that block's own cell rather than the open air above it.
const ROUTINE_V15_GROUNDED_WALK_MODE = 'routine-v15-grounded-walk'
// v17 keeps v15's walk and v16's placement of a thing in the agent's own block column (the
// block it stands on is under its feet, not half a block to the north; v16 never published),
// and gives the held goal a success condition, a rolling step, the step's body-measured effect
// and a bound on steps that change nothing.
const ROUTINE_V17_WORKING_GOAL_MODE = 'routine-v17-working-goal'
// v18 keeps v17 and holds every action's refs to the handles this perception offered in that
// action's own namespace, by a per-request strict schema rather than prose. A block in the
// agent's own column is no walk target. A revise keeps the run of steps that changed nothing.
const ROUTINE_V18_OFFERED_REFS_MODE = 'routine-v18-offered-refs'
// v19 keeps v18 and offers each action only what the body can reach with it - a block in sight
// and within interaction reach, a walk only to a block the server says a body can stand at, an
// item or creature only within the reach of the collect or attack mode that names it - because
// r10's two refusals were a covered walk target and a collect without moving 18 blocks off.
const ROUTINE_V19_ACTIONABLE_REFS_MODE = 'routine-v19-actionable-refs'
// v20 keeps v19 and types the working goal: its success condition is an item and count the body
// measures, its step the intent's own action and ref, a step disagreeing with its intent or a met
// condition left unanswered is refused before the body acts, and only evidence that advances the
// condition or carries out the step counts as progress, because the independent r11 audit found
// 24 of 28 credited steps were the body merely moving and three met conditions unseen.
const ROUTINE_V20_TYPED_GOAL_MODE = 'routine-v20-typed-goal'
// v21 keeps v20 and grounds each step in this perception: what the agent stands in is named as a
// dig rather than a walk, the absence of any dropped item is stated, a refused decision is
// explained at the next review, and a several-item goal is suggested where several are offered,
// because r12's fourteen step mismatches were walks away from a bush stood in and collects of
// drops that did not exist.
const ROUTINE_V21_GROUNDED_STEP_MODE = 'routine-v21-grounded-step'
// v22 keeps v21 and holds a goal to evidence: a success item must be an item the agent can get
// now (held, dropped in sight, craftable, or dropped by a block in sight dug with what it holds),
// a step is credited only on the evidence it produced (the exact named target reached, an item
// gained, or a new drop), a dig that would leave the agent in a hole is never offered, and a
// threat flight halts routine work in flight, because the r13 audit found five agents chasing
// items they could not get and 22 zero-yield digs credited as progress.
const ROUTINE_V22_OBTAINABLE_GOAL_MODE = 'routine-v22-obtainable-goal'
// v23 keeps v22 and grounds a goal in the agent's own evidence: a goal already met or pursued by
// idling is refused, the success condition and the step are typed by the body from the item, the
// count and the intent, a success count must lie within what sight could give, a dig is credited
// only for the drop the server attributed to its own request and that drop is told to the digger
// wherever it goes, a block's yields state their random range, and a settled goal step owes one
// follow-up review, because the r14 audit found three completions of goals met when set, a credit
// for another agent's drop, and goals that could advance at most once per two-minute review.
const ROUTINE_V23_OWN_EVIDENCE_MODE = 'routine-v23-own-evidence'
// v24 keeps v23 and makes a held goal answerable only by evidence it can produce: a collect walks
// to a cell whose pickup box covers the item, an item the body found no route to is marked and
// counted on by nothing, the agent's own drops are reported and offered even out of sight, a goal
// refusal is told with its numbers at the next review, continue is held to the obtainability rule
// adopt and revise are and the count bounds are in the request schema, a drop is credited once and
// is no progress until held, a step is measured once, a held goal must be answered and its dig or
// collect must be able to give its item, a stalled answer does not act, and the wording is the
// body's from the typed total, because the r15 audit found five noRoute collects of one stick, eight
// unobtainable refusals the mind never heard of, and a drop credit loop that kept the stall bound
// from firing.
const ROUTINE_V24_ANSWERED_GOAL_MODE = 'routine-v24-answered-goal'
// v25 keeps v24 and closes the schema gaps the r16 audit found: a goal review pairs its goal
// choice with the intent it may travel with, so adopt, continue, revise and replace never travel
// with idle or rest; a goal at the stall bound may only be completed if met or replaced by a new
// one in the same answer; while the agent's own drop of its goal's item lies in reach the step is
// a collect and dig is withheld; a walk is credited only onto a block that is itself a source of
// the goal's item; what sight can be counted on to give is each block's expected yield, its best
// case stated apart; own drops are told until their fate is known; and every review's record
// states the goal and refusal the mind was shown.
const ROUTINE_V25_COLLECTED_GOAL_MODE = 'routine-v25-collected-goal'
// v26 keeps v25 and holds goal pursuit to positive bars the r17 audit asked for: cells at the edge
// of sight are offered as walks so an agent that sees nothing worth doing can look further; a step
// that leaves the agent's own drop of its goal's item lying owes a follow-up review to collect it;
// a replace that sets the same item at the same or a lower total keeps the stall count; a met goal
// can be completed and the next adopted in one answer; an achievable goal is not abandoned; the
// step's wording is the body's, from the intent; a hunter out of sight and far off raises no flight;
// and the gate's coherence must clear the profile's pre-registered bars.
const ROUTINE_V26_PURSUED_GOAL_MODE = 'routine-v26-pursued-goal'
// v27 keeps v26 and answers the r18 audit: a drop of the goal's item still lying in reach is no
// stall, and a replace of the same item keeps the count at any total; a held goal sight cannot meet
// is continued by a walk to the edge of sight, credited only when it brings a source into sight;
// a goal review idles or rests only where nowhere is offered to look, so a completion travels with
// an action; each goal item is offered only its own steps; a revise never changes the item; a
// hunter out of sight is news only within eight blocks; follow-ups owed while an own drop lies
// draw on the run's allowance; a walk onto a source already in dig reach is no arrival; and the
// run stands in a forest, where wood can be dug and crafted.
const ROUTINE_V27_WOODED_GOAL_MODE = 'routine-v27-wooded-goal'
// v28 keeps v27 and answers the r19 audit: a block's yield is the server's sampled mean, not the
// middle of its range; a goal's steps offer its recipe first and its surest digs next; only a
// gain of the goal's item, or of a direct input of a recipe for it, is progress; a revise changes
// the held total; a plain completion beside a next goal's source is a replace; a step that leaves
// an own drop of the goal's item lying, and a plain completion, owe a review; a dig that would
// leave its drop in a pocket out of reach is unsafe; and the run stands on plains a walk from the
// woods, starting in the afternoon, so wood must be looked for and night falls inside the run.
const ROUTINE_V28_EXPLORED_GOAL_MODE = 'routine-v28-explored-goal'
// v29 keeps v28 and answers the r20 audit: a perception that ends without an action gives its
// body sense back, and one refused at capacity is recorded; a goal review may flee from a hunter
// near it and keep its goal; a threat answered without a flight is asked about again; a hunter in
// sight past the perception radius is no news, and a farther threat waits while a nearer one is
// answered; a body states a route to a threat only when it searched for one; a dig underfoot needs
// a walk out of where the body lands; a review waits while the agent's own flight holds the body;
// a call refused at the harness ceiling is typed; and each scheduled review earns one follow-up,
// so the plan leaves the night's danger a third of the call ceiling.
const ROUTINE_V29_NIGHT_GOAL_MODE = 'routine-v29-night-goal'
// v30 keeps v29 and answers the r22 audit: the call reserve is pooled and a dead agent owes
// nothing; a sighting that never reached the mind gives its nightly allowance back; a goal review
// beside a hunter in pivotal range is pivotal; an event is offered no goal choice; and a goal may
// ask past what sight gives when a source, a typed recipe or the frontier grounds it, while one
// sight hands over whole may not crowd out looking further (gate generation 13).
const ROUTINE_V30_HORIZON_GOAL_MODE = 'routine-v30-horizon-goal'
// v31 keeps v30 and answers the r23 audit: a review with nothing to ground an item goal sets a
// discovery goal walked to the edge of sight and replaced by a goal for what it reveals; danger is
// coalesced by where each threat stands now and an urgent call draws on the pooled reserve; a
// flight ends clear of the border and above a ravine; reviews wait while danger is with the mind;
// a remembered source is walked to beside it; a walk to look further is no step while a source is
// in reach; a held goal may switch into a recipe made from what it gathered; and each perception
// is recorded as the mind is shown it (gate generation 14).
const ROUTINE_V31_DISCOVERY_GOAL_MODE = 'routine-v31-discovery-goal'
// v32 keeps v31 and answers the r24 audit: urgent danger preempts the reviews still due; danger is
// tracked by the creature and held damage told once its answer settles; weak or ranged danger is
// pivotal; only a survival response is taken beside an immediate hunter; the attack health floor
// is stated and no attack is offered near it; blocks are sensed fairly across kinds; a discovery
// is followed through its reveal, never abandoned before its stall bound, and met by a recalled
// source; and a reveal is the walk's own (gate generation 15).
const ROUTINE_V32_SURVIVAL_DISCOVERY_MODE = 'routine-v32-survival-discovery'
// v33 keeps v32 and answers the r34 audit: the run's exposure ends in the turn its cognition
// admission closes, so nothing new is sent, applied or hurt in the drain; an older danger answer's
// flight never replaces a newer one's; a damaged agent is shown the server's health after the hit;
// a flight states why it sprints; a walk that cannot be left is corrected once and the pathfinder's
// impossible diagonal jump is refused; an approach that stalled is not offered again from the same
// cell over the same ground; a goal review looks further for logs and berry bushes and walks
// beside one out of reach; a stalled goal's count stays with what is made from it; and an item no
// body could pick up is not offered, with a collect's no-route verified on the server's ground
// (gate generation 18).
const ROUTINE_V33_SIGHTED_EXPOSURE_MODE = 'routine-v33-sighted-exposure'
// v34 keeps v33 and answers the r35 audit: a craft may ask no more applications than its recipe
// allows; a block sight shows beyond dig reach is offered only where a stand a walk reaches digs
// it and sees it, and the cell beside it is one; and a plan of two follow-ups a review spends both
// on ordinary steps (gate generation 18).
const ROUTINE_V34_REACHED_SOURCE_MODE = 'routine-v34-reached-source'
// v35 keeps v34 and answers the r36 audit: a block past the world border is never offered, and a
// dig or placement the border has moved past is refused before any packet; a goal review is told
// its cover from the server's ground and may set a shelter goal as night nears, met only when the
// ground seals it in and stepped only by a descent, a closing with a solid block in hand, the equip
// that readies one, or a walk onto natural cover, fed by a resource subgoal when nothing solid is
// held; in a pit with no walk out no flight is offered and closing it is the response; a sealed
// agent is told of no hunter; and a refused goal step owes a review (gate generation 19).
const ROUTINE_V35_SHELTER_GOAL_MODE = 'routine-v35-shelter-goal'
// v36 keeps v35 and answers the r39 audit: an objective asks at least two more than is held and
// lying as the agent's own, so no goal one step meets is offered; more of the held goal's own item
// is a revise, which keeps the goal, and never a replace; every replace and abandon states why its
// goal ends - completed, impossible, unsafe, stale or superseded - and only a reason its evidence
// supports is offered or accepted; a shelter is one objective, its closer gathered as a step of its
// own with no resource subgoal, credited against the cover the cell it stands in has shown; an
// abandon owes a review as a plain completion does; inventory solidity is the server's own
// collision rule; a placement settles at the server's answer and never into the body's own cell;
// and the newest decision takes a busy body (gate generation 20).
const ROUTINE_V36_HELD_OBJECTIVE_MODE = 'routine-v36-held-objective'
// The two native-scheduler pilots share every condition except what each mode pins here.
// Category fairness is a fact about the JVM selector a run used, so v12 stays false for
// its committed evidence and v13, which runs on the 48-per-kind selector, attests true.
// The prompt versions the mind serves today (`cognition/crates/cognition/src/prompt.rs`).
// A configuration pinning any other version can only fail after it has run.
const SERVED_PROMPTS = Object.freeze({ routine: 'routine-v36', notable: 'notable-v36', pivotal: 'pivotal-v36' })
// Modes kept only so artifacts committed under them still validate; no run may select one.
const HISTORICAL_PRIMARY_BEHAVIOR_MODES = Object.freeze({
  [ROUTINE_V12_PRIMARY_BEHAVIOR_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v12', notable: 'notable-v12', pivotal: 'pivotal-v12' }),
    categoryFairSelection: false,
    callCeilings: Object.freeze({ perAgent: 4, global: 12, outstanding: 3 })
  }),
  [ROUTINE_V13_GOAL_CONTINUITY_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v13', notable: 'notable-v13', pivotal: 'pivotal-v13' }),
    categoryFairSelection: true
  }),
  [ROUTINE_V14_GROUNDED_WALK_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v14', notable: 'notable-v14', pivotal: 'pivotal-v14' }),
    categoryFairSelection: true
  }),
  [ROUTINE_V15_GROUNDED_WALK_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v15', notable: 'notable-v15', pivotal: 'pivotal-v15' }),
    categoryFairSelection: true
  }),
  [ROUTINE_V17_WORKING_GOAL_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v17', notable: 'notable-v17', pivotal: 'pivotal-v17' }),
    categoryFairSelection: true
  }),
  [ROUTINE_V18_OFFERED_REFS_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v18', notable: 'notable-v18', pivotal: 'pivotal-v18' }),
    categoryFairSelection: true
  }),
  [ROUTINE_V19_ACTIONABLE_REFS_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v19', notable: 'notable-v19', pivotal: 'pivotal-v19' }),
    categoryFairSelection: true
  }),
  [ROUTINE_V20_TYPED_GOAL_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v20', notable: 'notable-v20', pivotal: 'pivotal-v20' }),
    categoryFairSelection: true
  }),
  [ROUTINE_V21_GROUNDED_STEP_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v21', notable: 'notable-v21', pivotal: 'pivotal-v21' }),
    categoryFairSelection: true
  }),
  [ROUTINE_V22_OBTAINABLE_GOAL_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v22', notable: 'notable-v22', pivotal: 'pivotal-v22' }),
    categoryFairSelection: true
  }),
  [ROUTINE_V23_OWN_EVIDENCE_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v23', notable: 'notable-v23', pivotal: 'pivotal-v23' }),
    categoryFairSelection: true,
    followUpsPerReview: 1
  }),
  [ROUTINE_V24_ANSWERED_GOAL_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v24', notable: 'notable-v24', pivotal: 'pivotal-v24' }),
    categoryFairSelection: true,
    followUpsPerReview: 1
  }),
  [ROUTINE_V25_COLLECTED_GOAL_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v25', notable: 'notable-v25', pivotal: 'pivotal-v25' }),
    categoryFairSelection: true,
    followUpsPerReview: 1
  }),
  [ROUTINE_V26_PURSUED_GOAL_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v26', notable: 'notable-v26', pivotal: 'pivotal-v26' }),
    categoryFairSelection: true,
    followUpsPerReview: 2
  }),
  [ROUTINE_V27_WOODED_GOAL_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v27', notable: 'notable-v27', pivotal: 'pivotal-v27' }),
    categoryFairSelection: true,
    followUpsPerReview: 2
  }),
  [ROUTINE_V28_EXPLORED_GOAL_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v28', notable: 'notable-v28', pivotal: 'pivotal-v28' }),
    categoryFairSelection: true,
    followUpsPerReview: 2
  }),
  [ROUTINE_V29_NIGHT_GOAL_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v29', notable: 'notable-v29', pivotal: 'pivotal-v29' }),
    categoryFairSelection: true,
    followUpsPerReview: 1,
    eventCallReserveShare: 1 / 3
  }),
  [ROUTINE_V30_HORIZON_GOAL_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v30', notable: 'notable-v30', pivotal: 'pivotal-v30' }),
    categoryFairSelection: true,
    followUpsPerReview: 1,
    eventCallReserveShare: 1 / 3
  }),
  [ROUTINE_V31_DISCOVERY_GOAL_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v31', notable: 'notable-v31', pivotal: 'pivotal-v31' }),
    categoryFairSelection: true,
    followUpsPerReview: 1,
    eventCallReserveShare: 1 / 3
  }),
  [ROUTINE_V32_SURVIVAL_DISCOVERY_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v32', notable: 'notable-v32', pivotal: 'pivotal-v32' }),
    categoryFairSelection: true,
    followUpsPerReview: 1,
    eventCallReserveShare: 1 / 3
  }),
  [ROUTINE_V33_SIGHTED_EXPOSURE_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v33', notable: 'notable-v33', pivotal: 'pivotal-v33' }),
    categoryFairSelection: true,
    followUpsPerReview: 1,
    eventCallReserveShare: 1 / 3
  }),
  [ROUTINE_V34_REACHED_SOURCE_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v34', notable: 'notable-v34', pivotal: 'pivotal-v34' }),
    categoryFairSelection: true,
    followUpsPerReview: 1,
    eventCallReserveShare: 1 / 3
  }),
  [ROUTINE_V35_SHELTER_GOAL_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v35', notable: 'notable-v35', pivotal: 'pivotal-v35' }),
    categoryFairSelection: true,
    followUpsPerReview: 1,
    eventCallReserveShare: 1 / 3
  })
})
const PRIMARY_BEHAVIOR_MODES = Object.freeze({
  [ROUTINE_V36_HELD_OBJECTIVE_MODE]: Object.freeze({
    prompts: Object.freeze({ routine: 'routine-v36', notable: 'notable-v36', pivotal: 'pivotal-v36' }),
    categoryFairSelection: true,
    followUpsPerReview: 1,
    // The share of the global call ceiling the planned reviews must leave for events (r20).
    eventCallReserveShare: 1 / 3
  })
})
function configuredMode (config) {
  return config.mode ?? CRASH_CONTAINMENT_MODE
}

function primaryBehaviorModeOf (mode) {
  if (Object.hasOwn(PRIMARY_BEHAVIOR_MODES, mode)) return PRIMARY_BEHAVIOR_MODES[mode]
  if (Object.hasOwn(HISTORICAL_PRIMARY_BEHAVIOR_MODES, mode)) return HISTORICAL_PRIMARY_BEHAVIOR_MODES[mode]
  return undefined
}

function isHistoricalMode (mode) {
  return Object.hasOwn(HISTORICAL_PRIMARY_BEHAVIOR_MODES, mode)
}

function isPrimaryBehaviorMode (config) {
  return primaryBehaviorModeOf(configuredMode(config)) !== undefined
}

function postCrashReserveFor (config) {
  if (isPrimaryBehaviorMode(config)) return undefined
  return {
    crashAgent: config.proof.crashAgent,
    callsPerPeer: config.proof.postCrashCallsPerPeer
  }
}

/** The native source a primary-behavior request with [trigger] comes from. */
function nativeOriginOf (trigger) {
  if (trigger === 'goalReview') return 'nativeGoalReviewSchedule'
  // The one review a settled goal step owes, from routine-v23.
  if (trigger === 'stepSettled') return 'nativeGoalFollowUp'
  return 'nativeEventDriver'
}

function requestOriginFor (config, trigger) {
  if (!isPrimaryBehaviorMode(config)) return undefined
  return nativeOriginOf(trigger)
}

function createInterventionSchedule (config, startedMillis) {
  if (isPrimaryBehaviorMode(config)) {
    return {
      due: () => [],
      controlledCrashIssued: () => false
    }
  }

  let nextCycle = startedMillis
  let crashed = false
  return {
    due: (now) => {
      const interventions = []
      if (!crashed && now - startedMillis >= config.proof.crashAfterSeconds * 1000) {
        crashed = true
        interventions.push({
          type: 'controlledBodyCrash',
          agentId: config.proof.crashAgent,
          signal: 'SIGKILL'
        })
        interventions.push({
          type: 'operatorGoalReview',
          agentId: config.proof.crashAgent,
          command: `loombody think ${config.proof.crashAgent}`
        })
      }
      if (now >= nextCycle) {
        for (const agentId of config.agents) {
          if (crashed && agentId === config.proof.crashAgent) continue
          interventions.push({
            type: 'operatorGoalReview',
            agentId,
            command: `loombody think ${agentId}`
          })
        }
        nextCycle = now + config.proof.cycleIntervalSeconds * 1000
      }
      return interventions
    },
    controlledCrashIssued: () => crashed
  }
}

function primaryBehaviorStimulusRecord (config, thinkCommandsIssued) {
  if (!isPrimaryBehaviorMode(config)) return undefined
  return {
    mode: configuredMode(config),
    externalCognitionStimuli: 'none',
    harnessThinkCommandsIssued: thinkCommandsIssued,
    nativeSchedulersOnly: thinkCommandsIssued === 0,
    behaviorGateGeneration: GATE_GENERATION,
    profile: config.primaryBehavior.profile,
    nativeGoalReviewSchedule: structuredClone(
      config.primaryBehavior.nativeGoalReviewSchedule
    )
  }
}

module.exports = {
  CRASH_CONTAINMENT_MODE,
  HISTORICAL_PRIMARY_BEHAVIOR_MODES,
  PRIMARY_BEHAVIOR_MODES,
  ROUTINE_V12_PRIMARY_BEHAVIOR_MODE,
  ROUTINE_V13_GOAL_CONTINUITY_MODE,
  ROUTINE_V14_GROUNDED_WALK_MODE,
  ROUTINE_V15_GROUNDED_WALK_MODE,
  ROUTINE_V17_WORKING_GOAL_MODE,
  ROUTINE_V18_OFFERED_REFS_MODE,
  ROUTINE_V19_ACTIONABLE_REFS_MODE,
  ROUTINE_V20_TYPED_GOAL_MODE,
  ROUTINE_V21_GROUNDED_STEP_MODE,
  ROUTINE_V22_OBTAINABLE_GOAL_MODE,
  ROUTINE_V23_OWN_EVIDENCE_MODE,
  ROUTINE_V24_ANSWERED_GOAL_MODE,
  ROUTINE_V25_COLLECTED_GOAL_MODE,
  ROUTINE_V26_PURSUED_GOAL_MODE,
  ROUTINE_V27_WOODED_GOAL_MODE,
  ROUTINE_V28_EXPLORED_GOAL_MODE,
  ROUTINE_V29_NIGHT_GOAL_MODE,
  ROUTINE_V30_HORIZON_GOAL_MODE,
  ROUTINE_V31_DISCOVERY_GOAL_MODE,
  ROUTINE_V32_SURVIVAL_DISCOVERY_MODE,
  ROUTINE_V33_SIGHTED_EXPOSURE_MODE,
  ROUTINE_V34_REACHED_SOURCE_MODE,
  ROUTINE_V35_SHELTER_GOAL_MODE,
  ROUTINE_V36_HELD_OBJECTIVE_MODE,
  SERVED_PROMPTS,
  configuredMode,
  createInterventionSchedule,
  isHistoricalMode,
  isPrimaryBehaviorMode,
  postCrashReserveFor,
  primaryBehaviorModeOf,
  primaryBehaviorStimulusRecord,
  nativeOriginOf,
  requestOriginFor
}
