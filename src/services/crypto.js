import crypto from 'crypto';
import { encryptCBC, decryptCBC } from './agc1.js';

/**
 * Derives a 32-byte encryption key from the user-provided master key.
 * @param {string} masterKey 
 * @returns {Buffer}
 */
function getDerivedKey(masterKey) {
  return crypto.createHash('sha256').update(masterKey).digest();
}

/**
 * Encrypts plain text using Double Encryption: AES-256-GCM + AGC-1-CBC.
 * @param {string} text 
 * @param {string} masterKey 
 * @returns {string} Encrypted string in format "agc_v1:ivAGCHex:ciphertextAGCHex"
 */
export function encrypt(text, masterKey) {
  if (!text) return null;
  if (!masterKey) throw new Error('Master key is required for encryption');

  const key = getDerivedKey(masterKey);

  // Layer 1: Standard AES-256-GCM
  const ivAES = crypto.randomBytes(12); // Standard 12 bytes IV for GCM
  const cipherAES = crypto.createCipheriv('aes-256-gcm', key, ivAES);

  let aesEncrypted = cipherAES.update(text, 'utf8', 'hex');
  aesEncrypted += cipherAES.final('hex');
  const tag = cipherAES.getAuthTag().toString('hex');
  const aesResult = `${ivAES.toString('hex')}:${aesEncrypted}:${tag}`;

  // Layer 2: Custom AGC-1-CBC
  const ivAGC = crypto.randomBytes(16); // 16 bytes block size IV
  const agcCipher = encryptCBC(Buffer.from(aesResult, 'utf8'), key, ivAGC);

  return `agc_v1:${ivAGC.toString('hex')}:${agcCipher.toString('hex')}`;
}

/**
 * Decrypts cipher text. Supports both legacy AES-256-GCM and double encryption (AGC-1-CBC + AES-256-GCM).
 * @param {string} encryptedText 
 * @param {string} masterKey 
 * @returns {string} Decrypted plain text
 */
export function decrypt(encryptedText, masterKey) {
  if (!encryptedText) return null;
  if (!masterKey) throw new Error('Master key is required for decryption');

  const key = getDerivedKey(masterKey);

  // Check if it uses the new double encryption format
  if (encryptedText.startsWith('agc_v1:')) {
    const parts = encryptedText.split(':');
    if (parts.length !== 3) {
      throw new Error('Invalid AGC-1 encrypted text format');
    }

    const [, ivAGCHex, cipherAGCHex] = parts;
    const ivAGC = Buffer.from(ivAGCHex, 'hex');
    const cipherAGC = Buffer.from(cipherAGCHex, 'hex');

    // Decrypt Layer 2 (AGC-1-CBC)
    const aesResult = decryptCBC(cipherAGC, key, ivAGC).toString('utf8');

    // Decrypt Layer 1 (AES-256-GCM)
    const aesParts = aesResult.split(':');
    if (aesParts.length !== 3) {
      throw new Error('Invalid AES sub-content format');
    }

    const [ivAESHex, aesCipherHex, tagAESHex] = aesParts;
    const ivAES = Buffer.from(ivAESHex, 'hex');
    const tagAES = Buffer.from(tagAESHex, 'hex');

    const decipherAES = crypto.createDecipheriv('aes-256-gcm', key, ivAES);
    decipherAES.setAuthTag(tagAES);

    let decrypted = decipherAES.update(aesCipherHex, 'hex', 'utf8');
    decrypted += decipherAES.final('utf8');

    return decrypted;
  }

  // Fallback to Legacy AES-256-GCM
  const parts = encryptedText.split(':');
  if (parts.length !== 3) {
    throw new Error('Invalid legacy encrypted text format');
  }

  const [ivHex, encryptedHex, tagHex] = parts;
  const iv = Buffer.from(ivHex, 'hex');
  const tag = Buffer.from(tagHex, 'hex');

  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAuthTag(tag);

  let decrypted = decipher.update(encryptedHex, 'hex', 'utf8');
  decrypted += decipher.final('utf8');

  return decrypted;
}
