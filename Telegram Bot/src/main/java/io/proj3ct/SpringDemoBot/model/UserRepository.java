package io.proj3ct.SpringDemoBot.model;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface UserRepository extends CrudRepository<User, Long> {
    boolean existsByChatIdAndIsAdmin(Long chatId, boolean isAdmin);
    List<User> findByChatIdAndIsAdmin(Long chatId, boolean isAdmin);

    List<User> findByIsAdminTrue();
}
