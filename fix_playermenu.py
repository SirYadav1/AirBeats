import re

with open("app/src/main/java/com/darkxvenom/airbeats/ui/menu/PlayerMenu.kt", "r", encoding="utf-8") as f:
    lines = f.readlines()

new_lines = []
in_nested_lazy_column = False
nested_lazy_column_brace_level = 0
current_brace_level = 0

i = 0
while i < len(lines):
    line = lines[i]
    
    # Track braces to find the end of the nested LazyColumn
    if in_nested_lazy_column:
        current_brace_level += line.count('{') - line.count('}')
        if current_brace_level == nested_lazy_column_brace_level:
            in_nested_lazy_column = False
            i += 1
            continue # skip the closing brace of the nested LazyColumn

    # Replace the outer Column with LazyColumn
    if "val scrollState = rememberScrollState()" in line:
        i += 1
        continue
    
    if "Column(" in line and "modifier = Modifier" in lines[i+1] and "verticalScroll(scrollState)" in lines[i+4]:
        # Found the outer column!
        new_lines.append("    LazyColumn(\n")
        new_lines.append("        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),\n")
        new_lines.append("        contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp)\n")
        new_lines.append("    ) {\n")
        new_lines.append("        item {\n") # Wrap the header and volume card in an item block
        i += 6 # Skip the Column start lines
        continue
        
    # Find the nested LazyColumn and remove it
    if "LazyColumn(" in line and "modifier = Modifier.weight(1f).fillMaxWidth()," in lines[i+1]:
        # We need to close the first item block before the nested LazyColumn's items
        new_lines.append("        }\n")
        
        # Now skip the LazyColumn declaration
        in_nested_lazy_column = True
        nested_lazy_column_brace_level = current_brace_level
        # The LazyColumn declaration has 5 lines
        for j in range(i, i+6):
            nested_lazy_column_brace_level += lines[j].count('{') - lines[j].count('}')
        
        i += 6
        continue
        
    new_lines.append(line)
    current_brace_level += line.count('{') - line.count('}')
    i += 1

with open("app/src/main/java/com/darkxvenom/airbeats/ui/menu/PlayerMenu.kt", "w", encoding="utf-8") as f:
    f.writelines(new_lines)
    
print("PlayerMenu layout fixed!")
