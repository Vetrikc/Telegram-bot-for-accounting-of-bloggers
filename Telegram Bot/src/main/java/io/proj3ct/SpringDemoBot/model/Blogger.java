package io.proj3ct.SpringDemoBot.model;


import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

@Entity
public class Blogger {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nickname;
    private String link;
    private String tags;
    private int points;

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public int getPoints() { return points; }
    public void setPoints(int points) { this.points = points; }
}