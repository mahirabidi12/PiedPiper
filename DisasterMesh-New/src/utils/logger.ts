const prefix = '[DisasterMesh]';

export function logStep(scope: string, message: string, details?: unknown) {
  if (details === undefined) {
    console.log(`${prefix} ${scope}: ${message}`);
    return;
  }

  console.log(`${prefix} ${scope}: ${message}`, details);
}

export function logError(scope: string, message: string, error?: unknown) {
  console.error(`${prefix} ${scope}: ${message}`, error);
}
