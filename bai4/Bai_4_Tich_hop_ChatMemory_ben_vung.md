# BÁO CÁO BÀI BẬT: THIẾT KẾ CHATMEMORY BỀN VỮNG (PERSISTENT MEMORY) CHO BOOKING AGENT

**Môn học:** AI Integration in Action  
**Bài tập:** BÀI 4: Tích hợp - Thiết kế ChatMemory bền vững (Persistent Memory) cho Booking Agent (Mức độ Giỏi)  
**Hệ thống:** R-Hotels AI Booking Agent System  

---

## I. BỐI CẢNH VÀ PHÂN TÍCH VẤN ĐỀ (PROBLEM ANALYSIS)

### 1. Vấn đề của InMemoryChatMemory trên môi trường Production
Trong môi trường phát triển cục bộ (Local), ứng dụng Spring Boot chạy trên một đơn vị Java Virtual Machine (JVM) duy nhất. Do đó, việc sử dụng `InMemoryChatMemory` (lưu trữ lịch sử đoạn chat trong `Map` hoặc `List` thuộc bộ nhớ RAM của JVM) đáp ứng tốt và phản hồi nhanh.

Tuy nhiên, khi triển khai lên môi trường Production với hạ tầng Cloud:
* **Môi trường phân tán (Distributed Architecture):** Hệ thống được triển khai trên nhiều Kubernetes Pods (hoặc nhiều Instance Backend) nằm đằng sau bộ cân bằng tải (Load Balancer).
* **Mất trạng thái phiên làm việc (Session Loss / Stateless Violation):** Do Load Balancer áp dụng cơ chế điều hướng (ví dụ: Round Robin hoặc Least Connections), mỗi request từ một người dùng trong cùng một phiên chat có thể bị dẫn tới các Pods/Containers khác nhau. Do `InMemoryChatMemory` chỉ tồn tại trong bộ nhớ RAM local của container nhận request đầu tiên, các container tiếp theo hoàn toàn không nắm được lịch sử hội thoại cũ.
* **Mất dữ liệu khi khởi động lại (Volatile Memory):** Khi ứng dụng thực hiện Rolling Update, Restart, hoặc Auto-Scaling, các container bị hoán đổi hoặc tắt đi làm toàn bộ dữ liệu lịch sử hội thoại lưu ở RAM bị xóa hoàn toàn.

### 2. Giải pháp Kiến trúc Persistent Chat Memory với JdbcChatMemory
Để giải quyết triệt để vấn đề trên, hệ thống chuyển dịch từ cơ chế lưu trữ **In-Memory (Stateless Server)** sang **Centralized Persistence Storage (Database-backed Memory)** sử dụng `JdbcChatMemory` tích hợp với Cơ sở dữ liệu quan hệ MySQL.

* **Tập trung hóa dữ liệu (Centralization):** Tất cả các Pod/Instance backend đều kết nối chung về cơ sở dữ liệu MySQL. Dù request rơi vào Pod nào, Pod đó cũng sẽ lấy và cập nhật lịch sử chat từ MySQL.
* **Độc lập bộ nhớ (Stateless Backend Services):** Backend Server trở nên hoàn toàn không lưu trạng thái (Stateless), phục vụ tốt cho việc Scale-out (mở rộng hàng ngang).
* **Đảm bảo tính phân tách phiên (Session Isolation):** Quản lý phiên thông qua tham số `conversationId` động (dạng UUID). Mỗi khách hàng có một không gian lưu trữ riêng biệt dựa trên `conversationId`.

---

## II. GIẢI PHÁP THIẾT KẾ VÀ PHÂN TÁCH PHIÊN CHAT (SESSION DESIGN)

### 1. Luồng xử lý và Phân tách phiên chat
1. **Khởi tạo phiên (Session Initialization):**
   * Khi người dùng bắt đầu cuộc hội thoại (lượt chat đầu tiên), Client không gửi kèm `conversationId` (hoặc gửi `null`/rỗng).
   * Server tiếp nhận request, kiểm tra và chủ động sinh một mã ngẫu nhiên chuẩn **UUID v4** (ví dụ: `c8f12a3d-8b09-4a92-91f7-e43210ab0123`).
   * Mã UUID này được gắn vào thuộc tính cấu hình advisor: `ChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY` ("chat_memory_conversation_id").
   * Phản hồi (Response) trả về cho Client sẽ đi kèm mã `conversationId` mới sinh này.
2. **Duy trì phiên (Session Persistence):**
   * Các lượt chat tiếp theo, Client bắt buộc phải gửi lại `conversationId` đã nhận từ lượt chat đầu tiên trong Request Headers, Query Params, hoặc Request Body.
   * Controller trích xuất `conversationId` và truyền vào `ChatClient` qua Advisor Spec để truy xuất đúng lịch sử hội thoại tương ứng trong cơ sở dữ liệu.

### 2. Mô hình Cấu trúc Bảng MySQL (Database Schema)
`JdbcChatMemory` của Spring AI yêu cầu cấu trúc bảng để lưu giữ tin nhắn (Messages). Dưới đây là DDL tiêu chuẩn cho MySQL:

```sql
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(255) NOT NULL,
    message_type VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

---

## III. MÃ NGUỒN JAVA CẤU HÌNH VÀ CONTROLLER

### 1. Lớp Cấu hình `DatabaseChatMemoryConfig.java`
Lớp này đóng vai trò khởi tạo Bean `JdbcChatMemory` liên kết với `JdbcTemplate`, đồng thời cấu hình `ChatClient.Builder` tích hợp `MessageChatMemoryAdvisor`.

```java
package com.rhotels.booking.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.JdbcChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DatabaseChatMemoryConfig {

    /**
     * Khởi tạo Bean ChatMemory sử dụng JdbcChatMemory lưu trữ dữ liệu tập trung vào MySQL
     */
    @Bean
    public ChatMemory chatMemory(JdbcTemplate jdbcTemplate) {
        return new JdbcChatMemory(jdbcTemplate);
    }

    /**
     * Cấu hình ChatClient.Builder tích hợp sẵn MessageChatMemoryAdvisor
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory))
                .build();
    }
}
```

### 2. Lớp REST Controller `BookingAgentController.java`
Lớp Controller xử lý các request chat từ phía người dùng, áp dụng kỹ thuật lập trình phòng thủ (Defensive Programming) để khởi tạo UUID nếu thiếu `conversationId`.

```java
package com.rhotels.booking.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/booking")
public class BookingAgentController {

    private final ChatClient chatClient;

    public BookingAgentController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(
            @RequestParam(value = "message") String message,
            @RequestParam(value = "conversationId", required = false) String conversationId) {

        // Logic phòng thủ: Tự động khởi tạo UUID nếu Client không truyền conversationId
        String activeConversationId = conversationId;
        if (!StringUtils.hasText(activeConversationId)) {
            activeConversationId = UUID.randomUUID().toString();
        }

        // Gọi ChatClient và gắn conversationId vào Advisor Spec
        String aiResponse = this.chatClient.prompt()
                .user(message)
                .advisors(advisorSpec -> advisorSpec.param(
                        AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, activeConversationId))
                .call()
                .content();

        // Đóng gói kết quả trả về cho Client
        Map<String, Object> response = new HashMap<>();
        response.put("conversationId", activeConversationId);
        response.put("reply", aiResponse);

        return ResponseEntity.ok(response);
    }
}
```

---

## IV. THUYẾT MINH KIẾN TRÚC ĐỒNG BỘ DỮ LIỆU VÀ KHẢ NĂNG SCALE-OUT

### 1. Cơ chế đồng bộ dữ liệu (Data Synchronization Mechanism)
* **Luồng Đọc (Read Flow):**  
  Khi có request mới kèm theo `conversationId`, `MessageChatMemoryAdvisor` can thiệp trước khi gửi prompt tới LLM. Advisor thực hiện truy vấn xuống DB MySQL thông qua `JdbcChatMemory`:
  ```sql
  SELECT * FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ? ORDER BY created_at ASC;
  ```
  Lịch sử câu hỏi/câu trả lời cũ được load vào context và nối với tin nhắn mới của người dùng trước khi gửi cho LLM.
* **Luồng Ghi (Write Flow):**  
  Sau khi LLM tạo câu trả lời thành công, Advisor tự động bắt sự kiện và gọi `JdbcChatMemory` ghi cả 2 tin nhắn (User Message và Assistant Response) xuống cơ sở dữ liệu MySQL dưới dạng giao dịch (Database Transaction).

### 2. Lý do giải pháp giúp hệ thống hoạt động ổn định khi Scale-out
1. **Chuyển đổi Backend thành Stateless Services:**  
   Backend Server không giữ bất kỳ state (trạng thái) nào trong bộ nhớ RAM local. Bất kỳ Pod/Container nào trong cụm Kubernetes cũng có thể tiếp nhận và xử lý request bất kỳ lúc nào.
2. **Khả năng làm việc đồng nhất sau Load Balancer:**  
   Load Balancer không cần phải cấu hình "Sticky Sessions" (Session Affinity) phức tạp. Dù request bị đẩy đến Pod 1, Pod 2 hay Pod N, các Pod này đều truy xuất dữ liệu từ một cSDL MySQL duy nhất.
3. **Tính sẵn sàng và Khôi phục thảm họa (Resilience & Fault Tolerance):**  
   Khi một Pod bị sập, bị restarts do hết RAM, hoặc khi triển khai bản cập nhật mới (CI/CD Deployment), không một tin nhắn hay phiên làm việc nào của khách hàng bị mất. Khách hàng tiếp tục đoạn chat mà không hề nhận ra sự gián đoạn dưới hạ tầng.
4. **Mở rộng dễ dàng (Horizontal Scalability):**  
   Hệ thống có thể dễ dàng tăng từ 2 Pods lên 50 Pods trong các dịp cao điểm đặt phòng của R-Hotels mà vẫn đảm bảo tính nhất quán dữ liệu (Data Consistency) và trải nghiệm liền mạch cho từng khách hàng.

---
*Báo cáo được tổng hợp và phân tích cho hệ thống R-Hotels AI Booking Agent.*
