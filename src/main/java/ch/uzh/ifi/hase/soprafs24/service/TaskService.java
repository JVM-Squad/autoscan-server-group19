package ch.uzh.ifi.hase.soprafs24.service;

import ch.uzh.ifi.hase.soprafs24.repository.ApplicationsRepository;
import ch.uzh.ifi.hase.soprafs24.repository.TaskRepository;
import ch.uzh.ifi.hase.soprafs24.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs24.rest.dto.TaskPutDTO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ch.uzh.ifi.hase.soprafs24.entity.User;
import ch.uzh.ifi.hase.soprafs24.entity.Application;
import ch.uzh.ifi.hase.soprafs24.entity.Task;
import ch.uzh.ifi.hase.soprafs24.service.TodoService;
import ch.uzh.ifi.hase.soprafs24.constant.TaskStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@Transactional
public class TaskService {

    private final Logger log = LoggerFactory.getLogger(TaskService.class);
    private final UserService userService;
    private final TodoService todoService;
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final ApplicationsRepository applicationsRepository;

    @Autowired
    public TaskService(
            @Qualifier("taskRepository") TaskRepository taskRepository,
            ApplicationsRepository applicationsRepository,
            UserRepository userRepository,
                UserService userService, @Lazy TodoService todoService) {
        this.taskRepository = taskRepository;
        this.userService = userService;
        this.userRepository = userRepository;
        this.todoService = todoService;
        this.applicationsRepository= applicationsRepository;
    }

    public List<Task> getTasks() {
        return this.taskRepository.findAll();
    }

    public Task getTaskById(long id) {
        return this.taskRepository.findById(id);
    }

    public List<Task> getTasksByCreator(long userId) {
        return this.taskRepository.findByCreatorId(userId);
    }

    public List<User> getCandidatesForTaskWithId(long taskId) {
        return userService.getCandidatesByTaskId(taskId);
    }

    public List<Task> getTasksByApplicant(long userId) {
        return this.taskRepository.findTasksByApplicantId(userId);
    }

    public Task createTask(Task newTask, long userId) {
        User creator = userService.getUserById(userId);
        boolean valid = checkIfCreatorHasEnoughTokens(creator, newTask);
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "creator does not have enough credits");
        }
        newTask.setCreator(creator);
        newTask.setStatus(TaskStatus.CREATED);
        newTask = taskRepository.saveAndFlush(newTask);
        userService.subtractCoins(creator, newTask.getPrice());

        todoService.createDefaultTodo(newTask.getId(), creator.getToken(), newTask.getTitle());
        return newTask;
    }

    public void apply(TaskPutDTO taskPutDTO, String token){
        User candidate = userRepository.findUserByToken(token);
        //to check if there is a token or the token has been manipulated
        if (candidate==null || token.isEmpty() || taskPutDTO.getUserId()!=candidate.getId()){
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid token.");
        }
        long taskId= taskPutDTO.getTaskId();

        Task selectedTask = taskRepository.findById(taskId);
        if (selectedTask == null){
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The selected task does not exists.");
        }
        Application existingApplication = applicationsRepository.findByUserAndTask(candidate, selectedTask);
        if (existingApplication != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already applied.");
        }
        Application newApplication = new Application();
        newApplication.setTask(selectedTask);
        newApplication.setUser(candidate);
        applicationsRepository.saveAndFlush(newApplication);
    }

    public void selectCandidate(TaskPutDTO taskPutDTO, String token){
        // QUESTION DANA. AFTER ANSWER DELETE COMMENTS IN DTOMAPPER. necessary because the user in the task entity is saved as an entity and is not mappable with
        //taskputdto since there the userId is a long
        User helper = this.userRepository.findUserById(taskPutDTO.getHelperId());
        User taskCreator = userRepository.findUserById(taskPutDTO.getUserId());
        Task task = taskRepository.findById(taskPutDTO.getTaskId());
        Application application = this.applicationsRepository.findByUserAndTask(helper, task);

        //Check the taskCreator is performing the selection action
        if (!taskCreator.getToken().equals(token)){
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the creator of a task can choose the helper");
        }
        //Check the task was retrieved correctly
        if (task == null){
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The task was not found.");
        }
        //Check the application actually exists
        if (application == null){
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "This helper has not applied for the job.");
        }

        task.setHelper(helper);
        task.setStatus(TaskStatus.IN_PROGRESS);
        deleteApplicationsByTask(task, helper);
    }

    public void deleteTaskWithId(long taskId, String token) {
        Task taskToBeDeleted = this.taskRepository.findById(taskId);
        if (taskToBeDeleted == null) {
            throw new NoSuchElementException("Task not found with id: " + taskId);
        }
        User creator = taskToBeDeleted.getCreator();
        if (!checkPermissionToDeleteTask(token,creator.getId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "only the creator of this task is allowed to delete it");
        }
        applicationsRepository.deleteByTaskId(taskId);
        creator.addCoins(taskToBeDeleted.getPrice());
        taskRepository.delete(taskToBeDeleted);
    }

    public Task confirmTask(long taskId, String token){
        Task taskToBeConfirmed = this.taskRepository.findById(taskId);
        User creator = taskToBeConfirmed.getCreator();
        User helper = taskToBeConfirmed.getHelper();
        long currentUserId = userService.getUserIdByToken(token);

        if (currentUserId != creator.getId() && currentUserId != helper.getId()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Only the creator or helper of this task are authorized to confirm it.");
        }

        if (currentUserId == creator.getId() && taskToBeConfirmed.getStatus() != TaskStatus.CONFIRMED_BY_CREATOR) {
            if (taskToBeConfirmed.getStatus() == TaskStatus.CONFIRMED_BY_HELPER) {
                taskToBeConfirmed.setStatus(TaskStatus.DONE);
                userService.addCoins(helper, taskToBeConfirmed.getPrice());
            } else {
                taskToBeConfirmed.setStatus(TaskStatus.CONFIRMED_BY_CREATOR);
            }
        } else if (currentUserId == helper.getId() && taskToBeConfirmed.getStatus() != TaskStatus.CONFIRMED_BY_HELPER) {
            if (taskToBeConfirmed.getStatus() == TaskStatus.CONFIRMED_BY_CREATOR) {
                taskToBeConfirmed.setStatus(TaskStatus.DONE);
                userService.addCoins(helper, taskToBeConfirmed.getPrice());
            } else {
                taskToBeConfirmed.setStatus(TaskStatus.CONFIRMED_BY_HELPER);
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You have already confirmed this task.");
        }

        taskRepository.save(taskToBeConfirmed);
        return taskToBeConfirmed;
    }

    @Transactional
    public void deleteCandidate(long taskId, String token){
        Task task = this.taskRepository.findById(taskId);
        User candidate = userRepository.findByToken(token);

        if (task.getCandidates().contains(candidate)) {
            task.getCandidates().remove(candidate);
            candidate.getApplications().remove(task);
            taskRepository.save(task);
            userRepository.save(candidate);
        } else {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The current user is not a candidate for this task");
        }
    }

    private boolean checkIfCreatorHasEnoughTokens(User creator, Task task) {
        return creator.getCoinBalance() >= task.getPrice();
    }

    private boolean checkPermissionToDeleteTask(String token, long creatorId) {
        long currentUserId = userService.getUserIdByToken(token);
        return currentUserId == creatorId;
    }

    public void deleteApplicationsByTask(Task task, User helper){
        long helperId= helper.getId();
        List<Application> applicationList = applicationsRepository.findApplicationsByTaskIdExcludingHelperId(task.getId(), helperId);
        for (Application application : applicationList) {
            applicationsRepository.delete(application);
        }
    }
}

