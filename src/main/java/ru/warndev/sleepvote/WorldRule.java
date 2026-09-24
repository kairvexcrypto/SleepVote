package ru.warndev.sleepvote;

public record WorldRule(String name, int percentage, int minimumPlayers, int minimumVotes, int quorumSeconds,
                        boolean virtualVotes, boolean clearWeather, boolean showBossbar,
                        int nightStart, int nightEnd, int morningTime) {
    public WorldRule {
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("Неверное имя мира");
        }
        if (percentage < 1 || percentage > 100 || minimumPlayers < 1 || minimumPlayers > 10000
                || minimumVotes < 1 || minimumVotes > 10000 || quorumSeconds < 1 || quorumSeconds > 120) {
            throw new IllegalArgumentException("Параметры голосования выходят за допустимые пределы");
        }
        if (nightStart < 12000 || nightStart >= nightEnd || nightEnd > 23999
                || morningTime < 0 || morningTime >= 12000) {
            throw new IllegalArgumentException("Неверный диапазон ночи или времени утра");
        }
    }

    public boolean night(long time) {
        long dayTime = Math.floorMod(time, 24000);
        return dayTime >= nightStart && dayTime <= nightEnd;
    }

    public int required(int eligible) {
        if (eligible < 0 || eligible > 10000) {
            throw new IllegalArgumentException("Неверное число игроков");
        }
        return Math.max(minimumVotes, (eligible * percentage + 99) / 100);
    }
}
