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

module.exports = {
  COGNITION_FAILURE_VERSION,
  validatedCognitionFailure
}
