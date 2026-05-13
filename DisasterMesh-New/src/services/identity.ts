import * as Crypto from 'expo-crypto';

export function createId(prefix: string) {
  return `${prefix}-${Crypto.randomUUID()}`;
}

export async function hashPassword(password: string, salt: string) {
  return Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, `${salt}:${password}`);
}
