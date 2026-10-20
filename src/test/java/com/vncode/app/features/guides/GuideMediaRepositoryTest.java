package com.vncode.app.features.guides;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class GuideMediaRepositoryTest {
    @TempDir Path root;
    @Test void localMediaSurvivesRestartAndReplacesOnlyItsGuideAndKind()throws Exception {
        var file=root.resolve("media.json");var repo=new GuideMediaRepository(file);
        repo.save(new GuideMediaRepository.Media("gs1",GuideMediaRepository.Kind.VIDEO,root.resolve("one.mp4").toUri()));
        repo.save(new GuideMediaRepository.Media("gs1",GuideMediaRepository.Kind.IMAGE,root.resolve("image.png").toUri()));
        repo.save(new GuideMediaRepository.Media("gs1",GuideMediaRepository.Kind.VIDEO,root.resolve("two.mp4").toUri()));
        var saved=new GuideMediaRepository(file).list();assertEquals(2,saved.size());assertTrue(saved.stream().anyMatch(m->m.uri().equals(root.resolve("two.mp4").toUri())));
    }
    @Test void mediaCannotIncludeCredentialsOrUntrustedNetworkSources(){var repo=new GuideMediaRepository(root.resolve("media.json"));assertThrows(IllegalArgumentException.class,()->repo.save(new GuideMediaRepository.Media("gs1",GuideMediaRepository.Kind.VIDEO,URI.create("https://user:secret@vncode.id.vn/a.mp4"))));assertThrows(IllegalArgumentException.class,()->repo.save(new GuideMediaRepository.Media("gs1",GuideMediaRepository.Kind.VIDEO,URI.create("http://127.0.0.1/a.mp4"))));}
}
