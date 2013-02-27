package com.wolvencraft.yasp.db.tables.normal;

public enum TotalBlocks implements _NormalTable {
	
	TableName("total_blocks"),
	
	EntryId("total_blocks_id"),
	MaterialId("material_id"),
	MaterialData("material_data"),
	PlayerId("player_id"),
	Destroyed("destroyed"),
	Placed("placed");
	
	TotalBlocks(String columnName) {
		this.columnName = columnName;
	}
	
	private String columnName;
	
	@Override
	public String toString() { return columnName; }
}
