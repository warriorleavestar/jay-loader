#version 150

#moj_import <amber_ffprobe:probe_util.glsl>

in vec3 Position;

uniform mat4 ProjMat;
uniform mat4 ModelViewMat;

void main() {
    gl_Position = amber_project(ProjMat, ModelViewMat, Position);
}
