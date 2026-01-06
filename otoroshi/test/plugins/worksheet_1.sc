dockerClient.execInContainer(
  "sh", "-c",
  "docker run -d -p 5000:5000 --restart=always --name registry registry:2 || true"
)

// Tag and push
dockerClient.execInContainer("docker", "tag", s"$imageName:latest", s"localhost:5000/$imageName:latest")
dockerClient.execInContainer("docker", "push", s"localhost:5000/$imageName:latest")