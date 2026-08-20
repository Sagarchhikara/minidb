package com.minidb.record;

public class Row {
    private int id;
    private String name;
    private int age;

    public Row(int id, String name, int age) {
        this.id = id;
        this.name = name;
        this.age = age;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getAge() {
        return age;
    }

    @Override
    public String toString() {
        return "Row(id=" + id + ", name=" + name + ", age=" + age + ")";
    }
}
