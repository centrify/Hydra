package com.github.codegerm.hydra.schema;

public class ColumnSchema {
	
	private String name;
	private String type;
	public ColumnSchema(String name, String type) {
		this.name = name;
		this.type = type;
	}
	@Override
	public String toString() {
		return "Column [name=" + name + ", type=" + type + "]";
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getType() {
		return type;
	}
	public void setType(String type) {
		this.type = type;
	}

}
