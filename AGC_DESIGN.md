# Tài liệu Thiết kế Cơ chế Mã hóa Dữ liệu AGC-1 (AntiGravity Cipher v1)

Tài liệu này trình bày chi tiết về thiết kế, công thức toán học, thuật toán và độ phức tạp của **AGC-1**, cơ chế mã hóa đối xứng khối tùy chỉnh (custom block cipher) được phát triển riêng cho ứng dụng **VPSMonitor** nhằm tăng cường độ an toàn thông tin của các thông tin nhạy cảm (SSH Keys, Passphrases, mật khẩu máy chủ) thông qua việc kết hợp cơ chế mã hóa AES-256-GCM tiêu chuẩn và thuật toán AGC-1-CBC riêng biệt.

---

## 1. Tổng quan Kiến trúc thuật toán

AGC-1 là một thuật toán mã hóa khối (Block Cipher) được thiết kế theo cấu trúc mạng Feistel (Feistel Network) cổ điển kết hợp các phép biến đổi phi tuyến và khuếch tán dữ liệu hiện đại.

- **Kích thước khối (Block Size)**: 128-bit (16 bytes).
- **Kích thước khóa chủ (Master Key Size)**: 256-bit (32 bytes).
- **Số vòng lặp (Number of Rounds)**: $16$ vòng.
- **Chế độ mã hóa**: Cipher Block Chaining (CBC) kết hợp vector khởi tạo ngẫu nhiên (16-byte IV) và kỹ thuật đệm PKCS#7.
- **Cơ chế bảo vệ kép**: 
  1. Dữ liệu thô trước tiên được mã hóa bằng **AES-256-GCM** để có được tính xác thực (Authentication Tag) và bảo mật tiêu chuẩn công nghiệp.
  2. Bản mã của AES-256-GCM sau đó được mã hóa tiếp thông qua **AGC-1-CBC** với khóa phái sinh từ khóa chính. Điều này tạo ra hai lớp bảo vệ độc lập, triệt tiêu hoàn toàn khả năng giải mã trực tiếp nếu không biết chi tiết cấu trúc thuật toán AGC-1.

---

## 2. Các thành phần toán học chi tiết

### 2.1. Phân rã khối dữ liệu
Mỗi khối dữ liệu 128-bit $X$ được biểu diễn thành bốn từ 32-bit (unsigned 32-bit integers):
$$X = (A, B, C, D)$$
Trong đó:
- Nửa trái: $L = (A, B)$ (64-bit)
- Nửa phải: $R = (C, D)$ (64-bit)

### 2.2. Hộp thế phi tuyến (S-Box) tĩnh phái sinh từ RC4 KSA
Để đảm bảo tính phi tuyến (confusion), thuật toán sử dụng một S-Box kích thước 8-bit ($256$ phần tử) là một phép hoán vị sinh bởi thuật toán lập lịch khóa RC4 (Key Scheduling Algorithm) với một hạt giống cố định `"AntiGravityCipherV1Seed"`.

**Thuật toán sinh S-Box:**
1. Khởi tạo mảng $S$ sao cho $S[i] = i$ với mọi $i \in [0, 255]$.
2. Đặt $j = 0$ và chuỗi hạt giống $Seed$.
3. Với mỗi $i$ từ $0$ đến $255$:
   $$j = (j + S[i] + Seed[i \pmod{len(Seed)}]) \pmod{256}$$
   Tráo đổi giá trị giữa $S[i]$ và $S[j]$.

**Hàm thế 32-bit ($\text{Sub}_{32}$):**
Nhận vào một từ 32-bit $w$, tách thành 4 byte $b_3, b_2, b_1, b_0$ và áp dụng S-Box lên từng byte:
$$\text{Sub}_{32}(w) = (S[b_3] \ll 24) \mid (S[b_2] \ll 16) \mid (S[b_1] \ll 8) \mid S[b_0]$$

---

### 2.3. Cơ chế lập lịch khóa (Key Schedule)
Khóa chủ 256-bit $K$ được đưa qua hàm băm SHA-256 để tạo ra 16 cặp khóa vòng 32-bit $(K_{r,0}, K_{r,1})$ (tổng cộng 32 từ khóa, tương đương 128 bytes dữ liệu khóa).

**Thuật toán sinh khóa vòng:**
1. Tính toán chuỗi băm ban đầu:
   $$H_0 = \text{SHA-256}(K \mathbin{\Vert} \text{"AGC-1-Key-Schedule"})$$
2. Sinh các chuỗi băm tiếp theo bằng cách băm chuỗi trước đó nối với chỉ số:
   $$H_p = \text{SHA-256}(H_{p-1} \mathbin{\Vert} p) \quad \text{với } p \in [1, 3]$$
3. Nối các chuỗi $H_0, H_1, H_2, H_3$ thu được chuỗi byte khóa $KS$ có độ dài 128 bytes.
4. Chuyển đổi $KS$ thành 32 từ 32-bit: $W_0, W_1, \dots, W_{31}$.
5. Với mỗi vòng $r \in [0, 15]$, khóa vòng là:
   $$K_{r,0} = W_{2r}, \quad K_{r,1} = W_{2r+1}$$

---

### 2.4. Hàm vòng (Round Function - $F$)
Hàm vòng $F(C, D, K_0, K_1)$ nhận vào nửa phải $R = (C, D)$ và cặp khóa vòng $(K_0, K_1)$, thực hiện các phép biến đổi hỗn hợp đại số, logic và dịch bit để tạo ra độ khuếch tán (diffusion) tối đa:

1. **Trộn khóa (Key Mixing)**:
   $$C' = (C + K_0) \pmod{2^{32}}$$
   $$D' = D \oplus K_1$$
2. **Thế phi tuyến (Substitution)**:
   $$x = \text{Sub}_{32}(C')$$
   $$y = \text{Sub}_{32}(D')$$
3. **Khuếch tán bit (Bitwise Diffusion)**:
   Sử dụng phép dịch vòng bit trái ($\lll$) và dịch vòng bit phải ($\ggg$):
   $$x' = (x \lll 11) \oplus y$$
   $$y' = ((y \ggg 7) + x') \pmod{2^{32}}$$
4. **Thế phi tuyến cuối**:
   $$out_0 = \text{Sub}_{32}(x')$$
   $$out_1 = \text{Sub}_{32}(y')$$
5. **Đầu ra**: $F(C, D, K_0, K_1) = (out_0, out_1)$

---

### 2.5. Các bước mã hóa Feistel trong một vòng
Với mỗi vòng thứ $r$ từ $0$ đến $15$:

**Mã hóa:**
Cho đầu vào vòng là $L_r = (A_r, B_r)$ và $R_r = (C_r, D_r)$:
$$L_{r+1} = R_r = (C_r, D_r)$$
$$(Y_0, Y_1) = F(C_r, D_r, K_{r,0}, K_{r,1})$$
$$R_{r+1} = L_r \oplus (Y_0, Y_1) = (A_r \oplus Y_0, B_r \oplus Y_1)$$

**Giải mã:**
Cho đầu vào vòng giải mã là $L_{r+1}$ và $R_{r+1}$:
$$R_r = L_{r+1}$$
$$(Y_0, Y_1) = F(L_{r+1}, K_{r,0}, K_{r,1})$$
$$L_r = R_{r+1} \oplus (Y_0, Y_1)$$

*Lưu ý: Nhờ cấu trúc Feistel, việc giải mã chỉ cần đảo ngược thứ tự các khóa vòng từ $r=15$ về $0$, hàm vòng $F$ vẫn giữ nguyên chiều hoạt động.*

---

## 3. Phân tích độ phức tạp thuật toán

Ký hiệu $N$ là kích thước dữ liệu cần mã hóa (độ dài chuỗi byte đầu vào).

### 3.1. Độ phức tạp thời gian (Time Complexity)
1. **Khởi tạo và Sinh Khóa (Key Schedule)**:
   - S-Box sinh bằng vòng lặp 256 bước cố định $\Rightarrow O(1)$.
   - Khóa vòng được sinh bằng 4 phép băm SHA-256 kích thước cố định $\Rightarrow O(1)$.
2. **Quá trình mã hóa khối (Block Encryption)**:
   - Một khối 16 bytes thực hiện 16 vòng Feistel.
   - Mỗi vòng Feistel thực hiện:
     - 4 phép thế qua mảng S-Box $\Rightarrow O(1)$
     - Các phép toán số học cộng/trừ 32-bit và logic bit $\Rightarrow O(1)$
   - Tổng thời gian xử lý cho một khối là hằng số $\Rightarrow O(1)$.
3. **Mã hóa chuỗi dữ liệu đầu vào**:
   - Số lượng khối cần mã hóa là $M = \lceil N / 16 \rceil$.
   - Mỗi khối được xử lý tuần tự trong chế độ CBC.
   - Tổng thời gian: $M \times O(1) \approx O(N)$.

$$\text{Độ phức tạp thời gian tổng quát: } \mathcal{O}(N)$$

---

### 3.2. Độ phức tạp không gian (Space Complexity)
1. **Bộ nhớ lưu trữ tĩnh**:
   - Mảng S-Box chiếm 256 bytes bộ nhớ $\Rightarrow O(1)$.
   - Danh sách khóa vòng lưu trữ 32 từ 32-bit (128 bytes) $\Rightarrow O(1)$.
2. **Bộ nhớ động (Auxiliary Space)**:
   - Các biến trung gian tính toán trong từng vòng đều có kích thước cố định 32-bit $\Rightarrow O(1)$.
   - Bộ nhớ tạm cho chế độ CBC là kích thước của khối hiện tại (16 bytes) $\Rightarrow O(1)$.

$$\text{Độ phức tạp không gian phụ trợ (Auxiliary Space): } \mathcal{O}(1)$$

---

## 4. Tóm tắt các ưu điểm của thiết kế AGC-1
1. **An toàn bảo mật kép**: Tận dụng triệt để độ an toàn xác thực của AES-256-GCM kết hợp sự xáo trộn phi tuyến mạnh mẽ của AGC-1.
2. **Độc lập và Khác biệt**: Không sử dụng lại các thuật toán phổ thông đơn thuần, chống lại các công cụ quét tự động tìm kiếm lỗ hổng cấu trúc AES hoặc giải mã trực tiếp từ file SQLite.
3. **Hiệu suất tối ưu**: Mã nguồn được tối ưu hóa bằng các phép toán bitwise 32-bit nguyên bản của Javascript Engine, tốc độ xử lý nhanh chóng, dung lượng RAM sử dụng tối thiểu (< 1MB).
