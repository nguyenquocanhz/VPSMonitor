import crypto from 'crypto';

// Dynamically initialized 8-bit S-Box using RC4 KSA with a fixed seed
const S_BOX = new Uint8Array(256);
const SEED = 'AntiGravityCipherV1Seed';

(function initSBox() {
  for (let i = 0; i < 256; i++) {
    S_BOX[i] = i;
  }
  let j = 0;
  for (let i = 0; i < 256; i++) {
    j = (j + S_BOX[i] + SEED.charCodeAt(i % SEED.length)) % 256;
    const temp = S_BOX[i];
    S_BOX[i] = S_BOX[j];
    S_BOX[j] = temp;
  }
})();

/**
 * 32-bit left circular rotation.
 */
function rol(x, n) {
  return ((x << n) | (x >>> (32 - n))) >>> 0;
}

/**
 * 32-bit right circular rotation.
 */
function ror(x, n) {
  return ((x >>> n) | (x << (32 - n))) >>> 0;
}

/**
 * Byte-by-byte substitution of a 32-bit word using our S-Box.
 */
function substitute32(val) {
  const b0 = S_BOX[val & 0xff];
  const b1 = S_BOX[(val >>> 8) & 0xff];
  const b2 = S_BOX[(val >>> 16) & 0xff];
  const b3 = S_BOX[(val >>> 24) & 0xff];
  return ((b3 << 24) | (b2 << 16) | (b1 << 8) | b0) >>> 0;
}

/**
 * Key schedule: derives 32 subkeys of 32-bit unsigned integers from the master key.
 * @param {Buffer} masterKey 32-byte key
 * @returns {Uint32Array} 32 subkeys
 */
function deriveSubkeys(masterKey) {
  const h0 = crypto.createHash('sha256').update(Buffer.concat([masterKey, Buffer.from('AGC-1-Key-Schedule') || ''])).digest();
  const h1 = crypto.createHash('sha256').update(Buffer.concat([h0, Buffer.from([1])])).digest();
  const h2 = crypto.createHash('sha256').update(Buffer.concat([h1, Buffer.from([2])])).digest();
  const h3 = crypto.createHash('sha256').update(Buffer.concat([h2, Buffer.from([3])])).digest();

  const ks = Buffer.concat([h0, h1, h2, h3]);
  const subkeys = new Uint32Array(32);
  for (let i = 0; i < 32; i++) {
    subkeys[i] = ks.readUInt32LE(i * 4);
  }
  return subkeys;
}

/**
 * Non-linear Round Function F(C, D, K0, K1).
 */
function roundF(C, D, K0, K1) {
  const C_prime = (C + K0) >>> 0;
  const D_prime = (D ^ K1) >>> 0;

  const x = substitute32(C_prime);
  const y = substitute32(D_prime);

  const x_prime = (rol(x, 11) ^ y) >>> 0;
  const y_prime = (ror(y, 7) + x_prime) >>> 0;

  const out0 = substitute32(x_prime);
  const out1 = substitute32(y_prime);

  return [out0, out1];
}

/**
 * Encrypts a single 16-byte block.
 */
function encryptBlock(block, subkeys) {
  let A = block.readUInt32LE(0);
  let B = block.readUInt32LE(4);
  let C = block.readUInt32LE(8);
  let D = block.readUInt32LE(12);

  // 16 rounds Feistel Network
  for (let r = 0; r < 16; r++) {
    const K0 = subkeys[2 * r];
    const K1 = subkeys[2 * r + 1];
    const [Y0, Y1] = roundF(C, D, K0, K1);

    const nextA = C;
    const nextB = D;
    const nextC = (A ^ Y0) >>> 0;
    const nextD = (B ^ Y1) >>> 0;

    A = nextA;
    B = nextB;
    C = nextC;
    D = nextD;
  }

  const out = Buffer.alloc(16);
  out.writeUInt32LE(A, 0);
  out.writeUInt32LE(B, 4);
  out.writeUInt32LE(C, 8);
  out.writeUInt32LE(D, 12);
  return out;
}

/**
 * Decrypts a single 16-byte block.
 */
function decryptBlock(block, subkeys) {
  let A = block.readUInt32LE(0);
  let B = block.readUInt32LE(4);
  let C = block.readUInt32LE(8);
  let D = block.readUInt32LE(12);

  // 16 rounds Feistel Network in reverse order
  for (let r = 15; r >= 0; r--) {
    const K0 = subkeys[2 * r];
    const K1 = subkeys[2 * r + 1];
    // Since L_{r+1} = R_r, the inputs to F are (A, B)
    const [Y0, Y1] = roundF(A, B, K0, K1);

    const prevC = A;
    const prevD = B;
    const prevA = (C ^ Y0) >>> 0;
    const prevB = (D ^ Y1) >>> 0;

    A = prevA;
    B = prevB;
    C = prevC;
    D = prevD;
  }

  const out = Buffer.alloc(16);
  out.writeUInt32LE(A, 0);
  out.writeUInt32LE(B, 4);
  out.writeUInt32LE(C, 8);
  out.writeUInt32LE(D, 12);
  return out;
}

/**
 * PKCS#7 Padding.
 */
function pad(buf, blockSize = 16) {
  const paddingLength = blockSize - (buf.length % blockSize);
  const paddingBuf = Buffer.alloc(paddingLength, paddingLength);
  return Buffer.concat([buf, paddingBuf]);
}

/**
 * PKCS#7 Unpadding.
 */
function unpad(buf) {
  if (buf.length === 0) throw new Error('Cannot unpad empty buffer');
  const paddingLength = buf[buf.length - 1];
  if (paddingLength < 1 || paddingLength > 16) {
    throw new Error('Invalid padding');
  }
  for (let i = buf.length - paddingLength; i < buf.length; i++) {
    if (buf[i] !== paddingLength) {
      throw new Error('Invalid padding bytes');
    }
  }
  return buf.subarray(0, buf.length - paddingLength);
}

/**
 * Encrypts data in CBC mode using AGC-1.
 * @param {Buffer} dataPlain
 * @param {Buffer} masterKey
 * @param {Buffer} iv
 * @returns {Buffer}
 */
export function encryptCBC(dataPlain, masterKey, iv) {
  const subkeys = deriveSubkeys(masterKey);
  const padded = pad(dataPlain, 16);
  const blocksCount = padded.length / 16;
  const resultBlocks = [];

  let prevBlock = iv;
  for (let i = 0; i < blocksCount; i++) {
    const block = padded.subarray(i * 16, (i + 1) * 16);
    // XOR with previous cipher block (CBC mode)
    const xored = Buffer.alloc(16);
    for (let j = 0; j < 16; j++) {
      xored[j] = block[j] ^ prevBlock[j];
    }
    const encrypted = encryptBlock(xored, subkeys);
    resultBlocks.push(encrypted);
    prevBlock = encrypted;
  }

  return Buffer.concat(resultBlocks);
}

/**
 * Decrypts data in CBC mode using AGC-1.
 * @param {Buffer} dataCipher
 * @param {Buffer} masterKey
 * @param {Buffer} iv
 * @returns {Buffer}
 */
export function decryptCBC(dataCipher, masterKey, iv) {
  if (dataCipher.length % 16 !== 0) {
    throw new Error('Ciphertext length must be a multiple of 16');
  }
  const subkeys = deriveSubkeys(masterKey);
  const blocksCount = dataCipher.length / 16;
  const resultBlocks = [];

  let prevBlock = iv;
  for (let i = 0; i < blocksCount; i++) {
    const block = dataCipher.subarray(i * 16, (i + 1) * 16);
    const decrypted = decryptBlock(block, subkeys);
    // XOR with previous cipher block (CBC mode)
    const xored = Buffer.alloc(16);
    for (let j = 0; j < 16; j++) {
      xored[j] = decrypted[j] ^ prevBlock[j];
    }
    resultBlocks.push(xored);
    prevBlock = block;
  }

  const paddedPlain = Buffer.concat(resultBlocks);
  return unpad(paddedPlain);
}
