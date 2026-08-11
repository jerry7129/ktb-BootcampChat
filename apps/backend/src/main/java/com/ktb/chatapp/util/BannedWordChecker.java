package com.ktb.chatapp.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.Assert;

/**
 * 금칙어 매칭기.
 * <p>
 * 금칙어 수가 많을 때(수천~수만) 메시지마다 모든 단어를 순회하며 contains()를 호출하면
 * 메시지 하나당 O(단어 수 * 메시지 길이)가 들어 매 채팅 전송마다 비용이 커진다.
 * 대신 생성 시점에 Aho-Corasick 트라이를 한 번 구축해두고, 검사 시점에는
 * 메시지 길이에만 비례하는 O(메시지 길이)로 다중 패턴을 동시에 매칭한다.
 */
public class BannedWordChecker {

    private final Node root = new Node();

    public BannedWordChecker(Set<String> bannedWords) {
        int wordCount = 0;
        for (String word : bannedWords) {
            if (word == null || word.isBlank()) {
                continue;
            }
            insert(word.toLowerCase(Locale.ROOT));
            wordCount++;
        }
        Assert.isTrue(wordCount > 0, "Banned words set must not be empty");

        buildFailureLinks();
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalized = message.toLowerCase(Locale.ROOT);

        Node current = root;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            while (current != root && !current.children.containsKey(c)) {
                current = current.fail;
            }
            current = current.children.getOrDefault(c, root);
            if (current.terminal) {
                return true;
            }
        }
        return false;
    }

    private void insert(String word) {
        Node current = root;
        for (int i = 0; i < word.length(); i++) {
            current = current.children.computeIfAbsent(word.charAt(i), c -> new Node());
        }
        current.terminal = true;
    }

    private void buildFailureLinks() {
        Deque<Node> queue = new ArrayDeque<>();
        for (Node child : root.children.values()) {
            child.fail = root;
            queue.add(child);
        }

        while (!queue.isEmpty()) {
            Node current = queue.poll();
            for (Map.Entry<Character, Node> entry : current.children.entrySet()) {
                char c = entry.getKey();
                Node child = entry.getValue();

                Node failCandidate = current.fail;
                while (failCandidate != root && !failCandidate.children.containsKey(c)) {
                    failCandidate = failCandidate.fail;
                }
                Node fail = failCandidate.children.get(c);
                child.fail = (fail != null && fail != child) ? fail : root;
                child.terminal = child.terminal || child.fail.terminal;

                queue.add(child);
            }
        }
    }

    private static final class Node {
        final Map<Character, Node> children = new HashMap<>();
        Node fail;
        boolean terminal;
    }
}
