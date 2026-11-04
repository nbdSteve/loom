'use strict'

const COGNITION_FAILURE_VERSION = 'loom-cognition-failure/1'

const RESPONSE_SHAPES = new Set([
  'notSent',
  'noHttpResponse',
  'unreadableHttpBody',
  'oversizedHttpBody',
  'apiErrorEnvelope',
  'otherJson',
  'malformedJson',
  'chatCompletion'
])

const MAPPINGS = new Set([
  'requestEncoding',
  'requestBound',
  'providerTimeout',
  'providerConnection',
  'providerResponseRead',
  'providerTransport',
  'providerAuthentication',
  'providerModel',
  'providerRateLimit',
  'providerRequest',
  'providerServer',
  'providerHttp',
  'providerResponseBound',
  'providerResponseParse',
  'providerUsageMissing',
  'providerUsageInconsistent',
  'providerUsageJournal',
  'providerAnswerMissing',
  'providerSafetyRefusal',
  'providerAnswerCutOff',
  'providerAnswerEmpty',
  'providerDecisionParse'
])

const FAILURE_KEYS = [
  'backend',
  'httpStatus',
  'mapping',
  'model',
  'providerCode',
  'providerType',
  'responseShape',
  'schemaVersion'
]

const PROVIDER_IDENTIFIER = /^[A-Za-z0-9_.-]{1,80}$/

function expectedModel (models, importance) {
  if (importance === 'PIVOTAL') return models?.pivotal ?? null
  if (importance === 'PLANNING') return models?.planning ?? models?.pivotal ?? null
  if (importance === 'ROUTINE' || importance === 'NOTABLE') {
    return models?.ordinary ?? null
  }
  return null
}

function safeProviderIdentifier (value) {
  if (value === null) return null
  return typeof value === 'string' && PROVIDER_IDENTIFIER.test(value)
    ? value
    : undefined
}

function validatedCognitionFailure (value, { models, importance }) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  if (Object.keys(value).sort().join('\0') !== FAILURE_KEYS.join('\0')) return null
  const model = expectedModel(models, importance)
  const providerType = safeProviderIdentifier(value.providerType)
  const providerCode = safeProviderIdentifier(value.providerCode)
  if (
    value.schemaVersion !== COGNITION_FAILURE_VERSION ||
    value.backend !== 'openai' ||
    typeof model !== 'string' ||
    value.model !== model ||
    !(
      value.httpStatus === null ||
      (
        Number.isInteger(value.httpStatus) &&
        value.httpStatus >= 100 &&
        value.httpStatus <= 599
      )
    ) ||
    providerType === undefined ||
    providerCode === undefined ||
    !RESPONSE_SHAPES.has(value.responseShape) ||
    !MAPPINGS.has(value.mapping)
  ) {
    return null
  }
  return {
    schemaVersion: COGNITION_FAILURE_VERSION,
    backend: 'openai',
    model,
    httpStatus: value.httpStatus,
    providerType,
    providerCode,
    responseShape: value.responseShape,
    mapping: value.mapping
  }
}

// The mind's own refusals carry no provider classification, so each is named by its exact
// error frame (`server.rs` `answer`, `mind.rs` `decide`, `model.rs` `NO_ANSWER_OFFERED`).
const MIND_REFUSALS = new Map([
  ['cognition failed: the answer named a ref this perception did not offer for that action', 'mindUnofferedRef'],
  ['cognition failed: the request schema for this perception offers no answer', 'mindNoAnswerOffered']
])
const FAILURE_REASONS = new Set([...MAPPINGS, ...MIND_REFUSALS.values(), 'unclassified'])

// One closed public token for why cognition failed: the provider mapping when classified, else
// the mind's refusal, else `unclassified`. Never the message itself.
function failureReasonOf (message, classified) {
  if (classified !== null && classified !== undefined) return classified.mapping
  return MIND_REFUSALS.get(message) ?? 'unclassified'
}

module.exports = {
  COGNITION_FAILURE_VERSION,
  FAILURE_REASONS,
  MIND_REFUSALS,
  failureReasonOf,
  validatedCognitionFailure
}
