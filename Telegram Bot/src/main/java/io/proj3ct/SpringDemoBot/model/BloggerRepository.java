package io.proj3ct.SpringDemoBot.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BloggerRepository extends JpaRepository<Blogger, Long> {
    List<Blogger> findByPointsLessThan(int i);
}